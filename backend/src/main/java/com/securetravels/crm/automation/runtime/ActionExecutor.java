package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.field.FieldDef;
import com.securetravels.crm.automation.field.FieldRegistry;
import com.securetravels.crm.automation.field.FieldType;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.communications.SendDecision;
import com.securetravels.crm.communications.SendGateService;
import com.securetravels.crm.communications.SendRequest;
import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import com.securetravels.crm.webhook.RoundRobinService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Executes the eight effect actions (Phase 6 Module 2). Every action first
 * {@linkplain #beginEffect registers its effect row} in the step-effector
 * outbox — the same transaction, same unique {@code (run, step, attempt)}
 * key — so a re-entered execution of an already-applied attempt aborts at the
 * guard, before any side effect is performed. Side effects the domain cannot
 * make idempotent are thereby made exactly-once by the engine, not by the
 * recipient.
 *
 * <p>Rejections are data, never exceptions: a send the gate declines, a tag
 * already present, an action that does not apply to this entity come back as
 * SKIPPED. Only genuine failures (a lead with no owner and no rotatable
 * assignee, a dead webhook) throw, and the run layer turns those into
 * retry/failure-inbox facts.
 */
@Component
public class ActionExecutor {

    private final TaskRepository tasks;
    private final RoundRobinService roundRobin;
    private final UserRepository users;
    private final SendGateService sendGate;
    private final NotificationRepository notifications;
    private final WorkflowStepEffectRepository effects;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ActionExecutor(TaskRepository tasks, RoundRobinService roundRobin,
                          UserRepository users, SendGateService sendGate,
                          NotificationRepository notifications,
                          WorkflowStepEffectRepository effects,
                          JdbcTemplate jdbc, AuditService audit) {
        this.tasks = tasks;
        this.roundRobin = roundRobin;
        this.users = users;
        this.sendGate = sendGate;
        this.notifications = notifications;
        this.effects = effects;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public EffectOutcome execute(StepDefinition step, RuntimeSnapshot snapshot,
                                 WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        return switch (step.action()) {
            case CREATE_TASK -> createTask(step, snapshot, run, ledger, attempts);
            case ASSIGN_OWNER -> assignOwner(step, snapshot, run, ledger, attempts);
            case UPDATE_FIELD -> updateField(step, snapshot, run, ledger, attempts);
            case SEND_MESSAGE -> sendMessage(step, snapshot, run, ledger, attempts);
            case NOTIFY_USER -> notifyUser(step, snapshot, run, ledger, attempts);
            case ADD_TAG -> addTag(step, snapshot, run, ledger, attempts);
            case CALL_WEBHOOK -> callWebhook(step, snapshot, run, ledger, attempts);
            case ENROLL_SEQUENCE -> skipped(run, ledger, attempts,
                    "ENROLL_SEQUENCE: the Phase 5 sequence engine is not built yet; nothing was enrolled");
            case WAIT, BRANCH, STOP, REQUEST_APPROVAL -> throw new IllegalStateException(
                    "Scheduler special-cases " + step.action() + ", not the executor");
        };
    }

    // ------------------------------------------------------------ CREATE_TASK

    private EffectOutcome createTask(StepDefinition step, RuntimeSnapshot snapshot,
                                     WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "CREATE_TASK");

        Task.Type type = Task.Type.valueOf(str(step.config(), "type"));
        UUID leadId = switch (snapshot.entity()) {
            case "lead" -> snapshot.subjectId();
            case "booking" -> snapshot.asUuid("leadId");
            default -> null;
        };
        UUID bookingId = "booking".equals(snapshot.entity()) ? snapshot.subjectId() : null;

        UUID assigneeId = resolveAssignee(step.config(), snapshot);
        if (assigneeId == null) {
            return skipGuard(guard, "CREATE_TASK: no assignee resolved for the subject");
        }

        Instant dueAt = dueAt(step.config(), snapshot);
        Integer slaHours = intOrNull(step.config(), "slaHours");
        Instant sla = slaHours == null ? null : dueAt.plus(Duration.ofHours(slaHours));

        Task task = tasks.save(new Task(leadId, assigneeId, type, dueAt, sla));
        if (bookingId != null) {
            task.setBookingId(bookingId);
            tasks.save(task);
        }

        guard.refId(task.getId());
        guard.summary("created " + type + " task for " + snapshot.entity() + " " + snapshot.subjectId()
                + " assigned to " + assigneeId + " due " + dueAt);
        effects.save(guard);
        return EffectOutcome.applied();
    }

    private UUID resolveAssignee(Map<String, Object> config, RuntimeSnapshot snapshot) {
        String method = str(config, "assignee");
        if (method == null) {
            return null;
        }
        return switch (method) {
            case "OWNER" -> snapshot.asUuid("ownerId");
            case "ROUND_ROBIN" -> roundRobin.pickNextSalesUser().getId();
            case "ROLE" -> users.findFirstByRoleOrderByCreatedAtAsc(role(config)).map(User::getId).orElse(null);
            case "USER" -> {
                UUID userId = uuid(config, "userId");
                yield userId == null ? null : users.findById(userId).map(User::getId).orElse(null);
            }
            default -> null;
        };
    }

    private Instant dueAt(Map<String, Object> config, RuntimeSnapshot snapshot) {
        Integer dueInMinutes = intOrNull(config, "dueInMinutes");
        if (dueInMinutes != null) {
            return Instant.now().plus(Duration.ofMinutes(dueInMinutes));
        }
        String dueDateField = str(config, "dueDateField");
        if (dueDateField != null) {
            LocalDate d = snapshot.asDate(dueDateField);
            return d == null ? Instant.now() : d.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        return Instant.now();
    }

    // ----------------------------------------------------------- ASSIGN_OWNER

    private EffectOutcome assignOwner(StepDefinition step, RuntimeSnapshot snapshot,
                                      WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "ASSIGN_OWNER");
        if (!"lead".equals(snapshot.entity())) {
            return skipGuard(guard,
                    "ASSIGN_OWNER is lead-only; subject is a " + snapshot.entity());
        }
        String method = str(step.config(), "method");
        UUID ownerId;
        if ("ROUND_ROBIN".equals(method)) {
            ownerId = roundRobin.pickNextSalesUser().getId();
        } else if ("SPECIFIC".equals(method)) {
            ownerId = uuid(step.config(), "userId");
        } else {
            return skipGuard(guard, "ASSIGN_OWNER: unknown method " + method);
        }
        if (ownerId == null) {
            return skipGuard(guard, "ASSIGN_OWNER: no target user");
        }
        UUID previous = snapshot.asUuid("ownerId");
        jdbc.update("UPDATE leads SET owner_id = ?, updated_at = now() WHERE id = ?",
                ownerId, snapshot.subjectId());
        audit.record("lead", snapshot.subjectId(), AuditAction.UPDATE, "owner_id",
                previous == null ? null : previous.toString(), ownerId.toString());
        guard.refId(ownerId);
        guard.summary("assigned lead " + snapshot.subjectId() + " to " + ownerId + " (" + method + ")");
        effects.save(guard);
        return EffectOutcome.applied();
    }

    // ------------------------------------------------------------ UPDATE_FIELD

    private EffectOutcome updateField(StepDefinition step, RuntimeSnapshot snapshot,
                                      WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "UPDATE_FIELD");

        String field = str(step.config(), "field");
        FieldDef def = FieldRegistry.lookup(snapshot.entity(), field).orElse(null);
        if (def == null) {
            return skipGuard(guard,
                    "UPDATE_FIELD: no such field '" + field + "' on " + snapshot.entity());
        }
        if (!def.writable()) {
            return skipGuard(guard, "UPDATE_FIELD: '" + field + "' is not writable");
        }
        Object value = step.config().containsKey("value")
                ? coerce(def.type(), step.config().get("value"))
                : fromField(def, snapshot, str(step.config(), "fromField"));
        if (value == null) {
            return skipGuard(guard, "UPDATE_FIELD: no value supplied for '" + field + "'");
        }

        String[] tableCol = tableColumn(def.source());
        int changed = jdbc.update("UPDATE " + tableCol[0] + " SET " + tableCol[1] + " = ?, updated_at = now() WHERE id = ?",
                value, snapshot.subjectId());
        audit.record(snapshot.entity(), snapshot.subjectId(), AuditAction.UPDATE,
                def.source(), snapshot.value(field) == null ? null : String.valueOf(snapshot.value(field)),
                String.valueOf(value));
        guard.summary("wrote " + def.source() + " = " + value + (changed == 0 ? " (no row)" : ""));
        effects.save(guard);
        return EffectOutcome.applied();
    }

    // ----------------------------------------------------------- SEND_MESSAGE

    private EffectOutcome sendMessage(StepDefinition step, RuntimeSnapshot snapshot,
                                      WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "SEND_MESSAGE");

        SubjectType subjectType = switch (snapshot.entity()) {
            case "lead" -> SubjectType.LEAD;
            case "customer" -> SubjectType.CUSTOMER;
            case "booking" -> SubjectType.BOOKING;
            default -> null;
        };
        if (subjectType == null) {
            return skipGuard(guard,
                    "SEND_MESSAGE has no subject mapping for entity " + snapshot.entity());
        }

        String toField = str(step.config(), "to");
        String recipient = snapshot.asString(toField);
        if (recipient == null || recipient.isBlank()) {
            return skipGuard(guard,
                    "SEND_MESSAGE: field '" + toField + "' is empty on the subject");
        }

        String templateCode = str(step.config(), "templateCode");
        String channel = str(step.config(), "channel");
        Purpose purpose = Purpose.valueOf(str(step.config(), "purpose") == null
                ? "TRANSACTIONAL" : str(step.config(), "purpose"));
        List<String> bodyValues = resolveBodyValues(step.config(), snapshot);

        SendRequest request = switch (channel) {
            case "WHATSAPP" -> templateCode != null
                    ? SendRequest.whatsappTemplate(subjectType, snapshot.subjectId(), templateCode, recipient, bodyValues, null)
                    : SendRequest.whatsappFreeform(subjectType, snapshot.subjectId(), recipient,
                    bodyValues.isEmpty() ? "" : bodyValues.get(0), null);
            case "EMAIL" -> SendRequest.email(subjectType, snapshot.subjectId(), templateCode, recipient, bodyValues, null);
            case "SMS" -> SendRequest.sms(subjectType, snapshot.subjectId(), templateCode, recipient, bodyValues, null);
            default -> null;
        };
        if (request == null) {
            return skipGuard(guard, "SEND_MESSAGE: unknown channel " + channel);
        }

        SendDecision decision = sendGate.request(request);
        if (!decision.accepted()) {
            return skipGuard(guard,
                    "SEND_MESSAGE: send gate declined (" + decision.httpStatus() + "): " + decision.reason());
        }
        guard.refId(decision.messageId());
        guard.summary("sent " + channel + (templateCode == null ? " free-form" : " " + templateCode)
                + " to " + subjectType + " " + snapshot.subjectId());
        effects.save(guard);
        return EffectOutcome.applied();
    }

    // ------------------------------------------------------------- NOTIFY_USER

    private EffectOutcome notifyUser(StepDefinition step, RuntimeSnapshot snapshot,
                                     WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "NOTIFY_USER");

        String title = str(step.config(), "title");
        String body = str(step.config(), "body");
        List<UUID> targets = new ArrayList<>();
        UUID userId = uuid(step.config(), "userId");
        if (userId != null) {
            targets.add(userId);
        } else {
            Role role = role(step.config());
            if (role != null) {
                users.findAllByRoleOrderByCreatedAtAsc(role).stream()
                        .filter(User::isActive)
                        .map(User::getId)
                        .forEach(targets::add);
            }
        }
        if (targets.isEmpty()) {
            return skipGuard(guard, "NOTIFY_USER: no recipients resolved");
        }
        for (UUID target : targets) {
            notifications.save(new Notification(target, Notification.Channel.IN_APP,
                    title, body, "/automation/runs/" + run.getId()));
        }
        guard.summary("notified " + targets.size() + " user(s): " + title);
        effects.save(guard);
        return EffectOutcome.applied();
    }

    // ----------------------------------------------------------------- ADD_TAG

    private EffectOutcome addTag(StepDefinition step, RuntimeSnapshot snapshot,
                                 WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "ADD_TAG");

        UUID customerId = "customer".equals(snapshot.entity())
                ? snapshot.subjectId()
                : snapshot.asUuid("customer360Id");
        if (customerId == null) {
            return skipGuard(guard,
                    "ADD_TAG: no customer360 record to tag for " + snapshot.entity() + " " + snapshot.subjectId());
        }
        String tag = str(step.config(), "tag");
        int changed = jdbc.update("""
                UPDATE customer360
                   SET offer_tags = array_append(coalesce(offer_tags, ARRAY[]::text[]), ?),
                       updated_at = now()
                 WHERE id = ? AND NOT (? = ANY(coalesce(offer_tags, ARRAY[]::text[])))
                """, tag, customerId, tag);
        guard.refId(customerId);
        guard.summary(changed == 0 ? "tag '" + tag + "' already present on customer " + customerId
                : "tagged customer " + customerId + " with '" + tag + "'");
        effects.save(guard);
        return EffectOutcome.applied();
    }

    // ------------------------------------------------------------ CALL_WEBHOOK

    private EffectOutcome callWebhook(StepDefinition step, RuntimeSnapshot snapshot,
                                      WorkflowRun run, WorkflowRunStep ledger, int attempts) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, "CALL_WEBHOOK");

        String url = str(step.config(), "url");
        if (url == null || !url.startsWith("https://")) {
            return skipGuard(guard,
                    "CALL_WEBHOOK requires an https:// URL (Module 3 guardrail)");
        }
        String method = str(step.config(), "method") == null ? "GET" : str(step.config(), "method");
        int timeout = intOrNull(step.config(), "timeoutSeconds") == null
                ? 10 : intOrNull(step.config(), "timeoutSeconds");

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Accept", "application/json");
            HttpRequest req = switch (method) {
                case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
                case "PUT" -> builder.PUT(HttpRequest.BodyPublishers.noBody()).build();
                case "DELETE" -> builder.DELETE().build();
                case "PATCH" -> builder.method("PATCH", HttpRequest.BodyPublishers.noBody()).build();
                default -> builder.GET().build();
            };
            int status = client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            guard.summary("webhook " + method + " " + url + " -> HTTP " + status);
            effects.save(guard);
            return EffectOutcome.applied();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("CALL_WEBHOOK failed for " + url + ": " + e.getMessage(), e);
        }
    }

    // ----------------------------------------------------------------- helpers

    /** The outbox guard: durable claim of this effect attempt, inserted BEFORE
     *  any side effect so a re-entered attempt aborts on the unique key. */
    private WorkflowStepEffect beginEffect(WorkflowRun run, WorkflowRunStep ledger,
                                           int attempts, String effect) {
        WorkflowStepEffect guard = new WorkflowStepEffect(
                run.getId(), ledger.getStepId(), attempts, effect, null, "executing " + effect);
        return effects.saveAndFlush(guard);
    }

    private EffectOutcome skipped(WorkflowRun run, WorkflowRunStep ledger, int attempts, String reason) {
        WorkflowStepEffect guard = beginEffect(run, ledger, attempts, ledger.getAction());
        guard.summary(reason);
        effects.save(guard);
        return EffectOutcome.skipped(reason);
    }

    /** A skip decided AFTER the attempt's guard row was already begun: the same
     *  row carries the reason instead of a second (unique-key-colliding)
     *  insert, keeping one effect row per attempt. */
    private EffectOutcome skipGuard(WorkflowStepEffect guard, String reason) {
        guard.summary(reason);
        effects.save(guard);
        return EffectOutcome.skipped(reason);
    }

    private List<String> resolveBodyValues(Map<String, Object> config, RuntimeSnapshot snapshot) {
        List<String> out = new ArrayList<>();
        Object raw = config.get("bodyValues");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                String text = String.valueOf(item);
                StringBuilder resolved = new StringBuilder();
                int from = 0;
                while (true) {
                    int start = text.indexOf('{', from);
                    if (start < 0) {
                        resolved.append(text.substring(from));
                        break;
                    }
                    int end = text.indexOf('}', start);
                    if (end < 0) {
                        resolved.append(text, from, text.length());
                        break;
                    }
                    resolved.append(text, from, start);
                    String field = text.substring(start + 1, end);
                    Object v = snapshot.value(field);
                    resolved.append(v == null ? "{" + field + "}" : String.valueOf(v));
                    from = end + 1;
                }
                out.add(resolved.toString());
            }
        }
        return out;
    }

    private Object fromField(FieldDef def, RuntimeSnapshot snapshot, String field) {
        if (field == null) return null;
        return switch (def.type()) {
            case DATE -> {
                LocalDate d = snapshot.asDate(field);
                yield d == null ? null : java.sql.Date.valueOf(d);
            }
            case DATE_TIME -> {
                Instant i = snapshot.asInstant(field);
                yield i == null ? null : Timestamp.from(i);
            }
            case NUMBER -> snapshot.asNumber(field);
            case BOOLEAN -> snapshot.value(field) == null ? null : snapshot.asBoolean(field);
            case UUID -> snapshot.asUuid(field);
            case TEXT, ENUM -> snapshot.asString(field);
        };
    }

    /** The literal value → column-typed value, with the {@code today+N} date
     *  token the editor offers for DATE fields. */
    private Object coerce(FieldType type, Object value) {
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) {
            return type == FieldType.DATE || type == FieldType.DATE_TIME
                    ? value : BigDecimal.valueOf(((Number) value).doubleValue());
        }
        String s = String.valueOf(value);
        return switch (type) {
            case NUMBER -> new BigDecimal(s);
            case BOOLEAN -> Boolean.valueOf(s);
            case UUID -> UUID.fromString(s);
            case DATE -> {
                if (s.startsWith("today")) {
                    long days = parseTodayOffset(s);
                    yield java.sql.Date.valueOf(LocalDate.now(ZoneOffset.UTC).plusDays(days));
                }
                yield java.sql.Date.valueOf(LocalDate.parse(s));
            }
            case DATE_TIME -> Timestamp.from(Instant.parse(s));
            case TEXT, ENUM -> s;
        };
    }

    private long parseTodayOffset(String token) {
        if (token.equals("today")) return 0;
        String rest = token.substring("today".length());
        if (rest.isEmpty() || !rest.startsWith("+") || rest.length() == 1) return 0;
        try {
            return Long.parseLong(rest.substring(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String[] tableColumn(String source) {
        int dot = source.indexOf('.');
        if (dot <= 0 || dot == source.length() - 1) return new String[]{ source, "id" };
        return new String[]{ source.substring(0, dot), source.substring(dot + 1) };
    }

    private String str(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private Role role(Map<String, Object> config) {
        String r = str(config, "role");
        return r == null ? null : Role.valueOf(r);
    }

    private UUID uuid(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : v instanceof UUID u ? u : UUID.fromString(String.valueOf(v));
    }

    private Integer intOrNull(Map<String, Object> config, String key) {
        Object v = config.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return ((Number) v).intValue();
        return Integer.parseInt(String.valueOf(v));
    }
}