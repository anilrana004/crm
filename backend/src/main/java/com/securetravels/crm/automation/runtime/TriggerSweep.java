package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.TriggerKind;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.automation.domain.WorkflowVersionRepository;
import com.securetravels.crm.automation.event.AutomationEventRecord;
import com.securetravels.crm.automation.event.AutomationEventRecordRepository;
import com.securetravels.crm.automation.field.FieldDef;
import com.securetravels.crm.automation.field.FieldRegistry;
import com.securetravels.crm.automation.field.FieldType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The scheduled side of the trigger surface (Phase 6 Module 2).
 *
 * <p>CRON workflows fire on their cron expression for every subject of the
 * trigger's entity; DATE_OFFSET workflows fire for exactly the subjects whose
 * date field equals {@code today + offsetDays}. Each firing is materialised as
 * a pseudo automation event (actions {@code "cron"} / {@code "date-offset"})
 * so the shared outbox — and its per-fire unique key — gives the same
 * exactly-once guarantee every other trigger gets: repeatedly sweeping the
 * same window simply hits the unique key and adds nothing.
 */
@Service
public class TriggerSweep {

    private static final Logger log = LoggerFactory.getLogger(TriggerSweep.class);

    private final WorkflowVersionRepository versions;
    private final AutomationEventRecordRepository events;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;

    public TriggerSweep(WorkflowVersionRepository versions,
                        AutomationEventRecordRepository events,
                        ObjectMapper mapper,
                        JdbcTemplate jdbc) {
        this.versions = versions;
        this.events = events;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    @Scheduled(fixedDelayString = "${app.automation.trigger-poll-millis}",
            initialDelayString = "${app.automation.trigger-poll-millis}")
    public void runOnce() {
        Instant now = Instant.now();
        List<WorkflowVersion> actives = versions.findByStatusOrderByCreatedAtAsc(WorkflowStatus.ACTIVE);
        for (WorkflowVersion version : actives) {
            WorkflowDefinition def;
            try {
                def = mapper.readValue(version.getDefinition(), WorkflowDefinition.class);
            } catch (Exception e) {
                log.warn("[automation] trigger sweep: unparseable definition on {}", version.getId(), e);
                continue;
            }
            TriggerDefinition trigger = def.trigger();
            if (trigger == null) continue;
            switch (trigger.kind()) {
                case CRON -> {
                    if (CronMatcher.of(trigger.cron()).matches(now)) {
                        fireEntity(trigger.entity(), AutomationEventRelay.PSEUDO_ACTION_CRON,
                                now, minuteKey(now));
                    }
                }
                case DATE_OFFSET -> {
                    LocalDate target = LocalDate.now(ZoneOffset.UTC)
                            .plusDays(trigger.offsetDays() == null ? 0 : trigger.offsetDays());
                    fireCandidates(trigger.entity(), trigger.dateField(), target, now);
                }
                default -> {
                    // EVENT workflows arrive through a real domain publish.
                }
            }
        }
    }

    private void fireEntity(String entity, String action, Instant now, String windowKey) {
        String table = tableFor(entity);
        if (table == null) return;
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id FROM " + table);
        for (Map<String, Object> row : rows) {
            fire(entity, action, (UUID) row.get("id"), now, windowKey);
        }
    }

    private void fireCandidates(String entity, String dateField, LocalDate target, Instant now) {
        if (dateField == null) {
            return;
        }
        FieldDef def = FieldRegistry.lookup(entity, dateField).orElse(null);
        if (def == null || def.type() != FieldType.DATE) {
            log.warn("[automation] trigger sweep: {} is not a registered DATE field on {}; nothing fired",
                    dateField, entity);
            return;
        }
        String[] tableCol = tableColumn(def.source());
        if (tableCol == null) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM " + tableCol[0] + " WHERE " + tableCol[1] + " = CAST(? AS date)",
                java.sql.Date.valueOf(target));
        for (Map<String, Object> row : rows) {
            fire(entity, AutomationEventRelay.PSEUDO_ACTION_DATE_OFFSET,
                    (UUID) row.get("id"), now, target.toString());
        }
    }

    private void fire(String entity, String action, UUID subjectId, Instant now, String windowKey) {
        String eventKey = entity + ":" + action + ":" + subjectId + ":" + windowKey;
        try {
            events.save(new AutomationEventRecord(entity, action, subjectId, now, eventKey));
        } catch (DataIntegrityViolationException e) {
            // Same fire window already materialised; the sweep is idempotent.
        }
    }

    private String minuteKey(Instant now) {
        return DateTimeFormatter.ISO_INSTANT.format(now.truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
    }

    private String tableFor(String entity) {
        return switch (entity) {
            case "lead" -> "leads";
            case "booking" -> "bookings";
            case "payment" -> "payments";
            case "task" -> "tasks";
            case "batch" -> "batches";
            case "customer" -> "customer360";
            case "traveller" -> "travellers";
            default -> null;
        };
    }

    private String[] tableColumn(String source) {
        if (source == null) return null;
        int dot = source.indexOf('.');
        if (dot <= 0 || dot == source.length() - 1) return null;
        return new String[]{ source.substring(0, dot), source.substring(dot + 1) };
    }
}