package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.TriggerKind;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.automation.domain.WorkflowVersionRepository;
import com.securetravels.crm.automation.event.AutomationEventRecord;
import com.securetravels.crm.automation.event.AutomationEventRecordRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The relay: drains the transactional outbox into run creation (Phase 6
 * Module 2). It never subscribes to a live bus — a domain publish was already
 * durably recorded in the caller's transaction, and this loop turns PENDING
 * rows into runs. Rows are claimed with {@code FOR UPDATE SKIP LOCKED} so
 * concurrent instances each take a disjoint slice; each event is handled to
 * completion (mark PROCESSED / FAILED) in its own transaction.
 *
 * <p>Cron and date-offset workflows dispatch through the same relay: the
 * trigger sweep materialises pseudo-events (actions {@code "cron"} /
 * {@code "date-offset"}) and the relay matches them by entity + the workflow's
 * trigger kind, so every trigger path shares the one outbox contract.
 */
@Service
public class AutomationEventRelay {

    public static final String PSEUDO_ACTION_CRON = "cron";
    public static final String PSEUDO_ACTION_DATE_OFFSET = "date-offset";

    private static final Logger log = LoggerFactory.getLogger(AutomationEventRelay.class);

    private final AutomationEventRecordRepository events;
    private final WorkflowVersionRepository versions;
    private final WorkflowRunService engine;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final com.securetravels.crm.common.config.AppProperties.Automation cfg;

    public AutomationEventRelay(AutomationEventRecordRepository events,
                                WorkflowVersionRepository versions,
                                WorkflowRunService engine,
                                ObjectMapper mapper,
                                JdbcTemplate jdbc,
                                PlatformTransactionManager tm,
                                com.securetravels.crm.common.config.AppProperties props) {
        this.events = events;
        this.versions = versions;
        this.engine = engine;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
        this.cfg = props.getAutomation();
    }

    @Scheduled(fixedDelayString = "${app.automation.relay-poll-millis}",
               initialDelayString = "${app.automation.relay-poll-millis}")
    public void drain() {
        List<UUID> claimed = tx.execute(s ->
                jdbc.queryForList("""
                        SELECT id FROM automation_events
                         WHERE (status = 'PENDING'
                             OR (status = 'PROCESSING' AND updated_at < now() - interval '5 minutes'))
                         ORDER BY created_at
                         LIMIT ?
                         FOR UPDATE SKIP LOCKED
                        """, cfg.getPollBatchSize()).stream()
                        .map(row -> (UUID) row.get("id"))
                        .toList());
        if (claimed == null) return;
        for (UUID id : claimed) {
            process(id);
        }
    }

    public void process(UUID eventId) {
        try {
            tx.executeWithoutResult(s -> {
                AutomationEventRecord e = events.findById(eventId).orElse(null);
                if (e == null) return;
                e.markProcessing();
                events.save(e);
            });

            AutomationEventRecord event = events.findById(eventId).orElse(null);
            if (event == null) return;
            for (WorkflowVersion version : versions.findByStatusOrderByCreatedAtAsc(WorkflowStatus.ACTIVE)) {
                if (matches(version, event)) {
                    engine.startRun(version, event.getEntity(), event.getAction(),
                            event.getSubjectId(), event.getOccurredAt(), event.getEventKey());
                }
            }

            tx.executeWithoutResult(s -> {
                AutomationEventRecord e = events.findById(eventId).orElse(null);
                if (e == null) return;
                e.markProcessed();
                events.save(e);
            });
        } catch (Exception e) {
            log.warn("[automation] relay failed on event {}", eventId, e);
            tx.executeWithoutResult(s -> {
                AutomationEventRecord rec = events.findById(eventId).orElse(null);
                if (rec == null) return;
                if (rec.getAttempts() >= cfg.getEventMaxAttempts()) {
                    rec.markFailed(message(e));
                    events.save(rec);
                }
            });
        }
    }

    private boolean matches(WorkflowVersion version, AutomationEventRecord event) {
        WorkflowDefinition def;
        try {
            def = mapper.readValue(version.getDefinition(), WorkflowDefinition.class);
        } catch (Exception e) {
            log.warn("[automation] unparseable definition on version {}", version.getId(), e);
            return false;
        }
        TriggerDefinition trigger = def.trigger();
        if (trigger == null) return false;
        return switch (trigger.kind()) {
            case EVENT -> trigger.event() != null
                    && trigger.event().equals(event.getEntity() + "." + event.getAction());
            case CRON -> PSEUDO_ACTION_CRON.equals(event.getAction())
                    && trigger.entity() != null
                    && trigger.entity().equals(event.getEntity());
            case DATE_OFFSET -> PSEUDO_ACTION_DATE_OFFSET.equals(event.getAction())
                    && trigger.entity() != null
                    && trigger.entity().equals(event.getEntity());
        };
    }

    private String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}