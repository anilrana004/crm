package com.securetravels.crm.automation.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.ConditionNode;
import com.securetravels.crm.automation.definition.RetryPolicy;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.automation.domain.WorkflowVersionRepository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The run coordinator (Phase 6 Module 2).
 *
 * <p>The coordinator itself is deliberately not transactional: each
 * {@link #executeStep} commits in its own REQUIRES_NEW transaction, so a crash
 * between steps leaves the ledger consistent and the recovery sweep re-enters
 * the run at {@code run.position}. Exactly-once at the step level is enforced
 * by the step-effector outbox (a re-entered attempt of an already-applied
 * effect aborts at the unique key before doing anything) plus the ledger rule
 * that SUCCEEDED/SKIPPED steps are never executed twice.
 *
 * <p>Cancellation is re-checked at the top of every step: the pinned version
 * must still exist and not be ARCHIVED, and the run must be RUNNING/WAITING.
 */
@Service
public class WorkflowRunService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunService.class);
    private static final int MAX_COORDINATE_ITERATIONS = 10_000;

    private final WorkflowRunRepository runs;
    private final WorkflowRunStepRepository steps;
    private final WorkflowScheduledStepRepository scheduled;
    private final WorkflowRunFailureRepository failures;
    private final WorkflowVersionRepository versions;
    private final SubjectSnapshotLoader snapshots;
    private final ConditionEvaluator evaluator;
    private final ActionExecutor executor;
    private final ObjectMapper mapper;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final TransactionTemplate tx;
    private final TransactionTemplate txStep;

    public WorkflowRunService(WorkflowRunRepository runs, WorkflowRunStepRepository steps,
                              WorkflowScheduledStepRepository scheduled,
                              WorkflowRunFailureRepository failures,
                              WorkflowVersionRepository versions,
                              SubjectSnapshotLoader snapshots,
                              ConditionEvaluator evaluator,
                              ActionExecutor executor,
                              ObjectMapper mapper,
                              UserRepository users,
                              NotificationRepository notifications,
                              PlatformTransactionManager tm) {
        this.runs = runs;
        this.steps = steps;
        this.scheduled = scheduled;
        this.failures = failures;
        this.versions = versions;
        this.snapshots = snapshots;
        this.evaluator = evaluator;
        this.executor = executor;
        this.mapper = mapper;
        this.users = users;
        this.notifications = notifications;
        this.tx = new TransactionTemplate(tm);
        this.txStep = new TransactionTemplate(tm);
        this.txStep.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // -------------------------------------------------------------- trigger

    /**
     * Trigger the workflow version for an event. Returns the created run id,
     * or {@code null} when the run was coalesced (a live run already exists for
     * the subject, or the exact trigger was replayed) or declined (entry
     * condition false, subject gone, no steps).
     */
    public UUID startRun(WorkflowVersion version, String entity, String action,
                         UUID subjectId, Instant occurredAt, String eventKey) {
        WorkflowDefinition def = parse(version.getDefinition());
        if (def.steps().isEmpty()) {
            return null;
        }
        RuntimeSnapshot snapshot = snapshots.load(entity, subjectId);
        if (snapshot == null) {
            log.debug("[automation] {} {}: subject {} gone; declining", entity, action, subjectId);
            return null;
        }
        if (def.entryConditions() != null && !evaluator.matches(def.entryConditions(), snapshot)) {
            log.debug("[automation] {} {}: {} did not meet entry conditions; declining",
                    entity, action, subjectId);
            return null;
        }

        UUID runId;
        try {
            runId = tx.execute(status -> {
                WorkflowRun run = runs.saveAndFlush(new WorkflowRun(
                        version.getWorkflowId(), version.getId(), entity,
                        subjectId, eventKey, eventKey));
                int order = 0;
                for (StepDefinition step : def.steps()) {
                    steps.save(new WorkflowRunStep(run.getId(), step.id(), order++, step.action().name(), 0));
                }
                return run.getId();
            });
        } catch (DataIntegrityViolationException e) {
            // (workflow_id, event_key) duplicate = replayed trigger; the
            // partial active index = a live run already holds the subject.
            // Caught OUTSIDE the TransactionTemplate: the constraint violation
            // marks that transaction rollback-only, and TransactionTemplate
            // rethrows it here after the rollback — so coalescing is a clean
            // no-op, never an UnexpectedRollbackException in the caller.
            log.debug("[automation] coalesced trigger {} for workflow {}", eventKey, version.getWorkflowId());
            return null;
        }

        log.info("[automation] run {} started for {} {} ({})", runId, entity, subjectId, eventKey);
        String first = def.steps().stream().min(Comparator.comparingInt(StepDefinition::order))
                .map(StepDefinition::id).orElse(null);
        coordinate(runId, first);
        return runId;
    }

    /**
     * Drive the run from {@code fromStepId} until the run parks or terminates.
     * Non-transactional: executes one step per REQUIRES_NEW transaction with a
     * re-read of the run between stops, so a concurrent cancellation or a
     * re-entered poller observation is honoured mid-flight.
     */
    public void coordinate(UUID runId, String fromStepId) {
        String current = fromStepId;
        int guard = 0;
        while (current != null && guard <= MAX_COORDINATE_ITERATIONS) {
            WorkflowRun run = runs.findById(runId).orElse(null);
            if (run == null || !run.alive()) {
                return;
            }
            current = executeStep(runId, current);
            guard++;
        }
        if (current != null) {
            log.warn("[automation] run {} exceeded the coordination budget (last step {})", runId, current);
        }
    }

    /**
     * Execute exactly one step of a run in its own REQUIRES_NEW transaction
     * (guaranteed even though callers reach this method in-process, hence the
     * explicit TransactionTemplate rather than a proxy-only annotation);
     * returns the next step id to run, or {@code null} because the run parked
     * (WAIT / retry / approval) or went terminal. Safe to re-enter: a step in
     * the ledger is never executed twice.
     */
    public String executeStep(UUID runId, String stepId) {
        return txStep.execute(status -> executeStepInternal(runId, stepId));
    }

    private String executeStepInternal(UUID runId, String stepId) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null || !run.alive()) {
            return null;
        }

        WorkflowVersion version = versions.findById(run.getWorkflowVersionId()).orElse(null);
        if (version == null || version.getStatus() == WorkflowStatus.ARCHIVED) {
            log.info("[automation] cancel run {}: pinned version {}", runId,
                    version == null ? "missing" : "archived");
            run.cancel();
            runs.save(run);
            scheduled.deleteByRunId(runId);
            return null;
        }

        WorkflowDefinition def = parse(version.getDefinition());
        StepDefinition step = def.step(stepId);
        if (step == null) {
            failRun(run, "Pinned definition has no step '" + stepId + "'", stepId, 0);
            return null;
        }
        RuntimeSnapshot snapshot = snapshots.load(run.getEntity(), run.getSubjectId());
        if (snapshot == null) {
            log.info("[automation] run {}: subject {} {} no longer exists; cancelling",
                    runId, run.getEntity(), run.getSubjectId());
            run.cancel();
            runs.save(run);
            return null;
        }

        WorkflowRunStep ledger = steps.findByRunIdAndStepId(runId, stepId).orElseGet(() ->
                steps.save(new WorkflowRunStep(runId, stepId, step.order(), step.action().name(), 0)));

        if (ledger.finished()) {
            String next = advance(def, step, snapshot);
            run.position(next);
            runs.save(run);
            return next;
        }

        if (step.when() != null && !evaluator.matches(step.when(), snapshot)) {
            ledger.skipped("when guard evaluated false");
            steps.save(ledger);
            String next = successor(def, step);
            run.position(next);
            runs.save(run);
            return next;
        }

        switch (step.action()) {
            case WAIT -> {
                ledger.succeeded();
                steps.save(ledger);
                long minutes = Math.max(1, intConfig(step.config(), "minutes", 0));
                scheduled.save(new WorkflowScheduledStep(runId, stepId,
                        ScheduledStepKind.WAIT, Instant.now().plus(java.time.Duration.ofMinutes(minutes))));
                String next = successor(def, step);
                run.position(next);
                run.park();
                runs.save(run);
                log.info("[automation] run {} parked {} minutes on wait step {}", runId, minutes, stepId);
                return null;
            }
            case BRANCH -> {
                String target = branchTarget(def, step, snapshot);
                ledger.succeeded();
                steps.save(ledger);
                if (target == null) {
                    run.succeed();
                    run.position(null);
                    log.info("[automation] run {} branched to end", runId);
                } else {
                    run.position(target);
                }
                runs.save(run);
                return target;
            }
            case STOP -> {
                ledger.succeeded();
                steps.save(ledger);
                run.succeed();
                run.position(null);
                runs.save(run);
                log.info("[automation] run {} succeeded", runId);
                return null;
            }
            case REQUEST_APPROVAL -> {
                String roleName = strConfig(step.config(), "role");
                String message = strConfig(step.config(), "message");
                Role role = roleName == null ? null : Role.valueOf(roleName);
                String title = message == null ? "Automation approval needed" : message;
                if (role != null) {
                    for (User user : users.findAllByRoleOrderByCreatedAtAsc(role)) {
                        if (user.isActive()) {
                            notifications.save(new Notification(user.getId(), Notification.Channel.IN_APP,
                                    title, "Run " + runId + " is waiting on your decision",
                                    "/automation/runs/" + runId));
                        }
                    }
                }
                log.info("[automation] run {} parked on approval request to role {}", runId, roleName);
                ledger.succeeded();
                steps.save(ledger);
                scheduled.save(new WorkflowScheduledStep(runId, stepId,
                        ScheduledStepKind.APPROVAL, Instant.now()));
                String next = successor(def, step);
                run.position(next);
                run.park();
                runs.save(run);
                return null;
            }
            default -> {
                return effect(step, snapshot, run, ledger, def);
            }
        }
    }

    private String effect(StepDefinition step, RuntimeSnapshot snapshot,
                          WorkflowRun run, WorkflowRunStep ledger, WorkflowDefinition def) {
        int attempt = ledger.getAttempts() + 1;
        ledger.attempt(attempt);
        try {
            EffectOutcome outcome = executor.execute(step, snapshot, run, ledger, attempt);
            if (outcome.skipped()) {
                ledger.skipped(outcome.skipReason());
                steps.save(ledger);
                String next = successor(def, step);
                run.position(next);
                runs.save(run);
                log.info("[automation] run {} step {} skipped: {}", run.getId(), step.id(), outcome.skipReason());
                return next;
            }
            ledger.succeeded();
            steps.save(ledger);
            String next = successor(def, step);
            run.position(next);
            runs.save(run);
            return next;
        } catch (RuntimeException e) {
            int attemptsBudget = step.retry() == null ? RetryPolicy.NONE.maxAttempts() : step.retry().maxAttempts();
            long backoff = step.retry() == null ? 0 : step.retry().backoffMillis();
            run.position(step.id());
            if (attempt < attemptsBudget) {
                ledger.failed(message(e));
                steps.save(ledger);
                scheduled.save(new WorkflowScheduledStep(run.getId(), step.id(),
                        ScheduledStepKind.RETRY, Instant.now().plusMillis(backoff * attempt)));
                run.park();
                runs.save(run);
                log.warn("[automation] run {} step {} attempt {} failed; retry scheduled", run.getId(), step.id(), attempt, e);
                return null;
            }
            ledger.failed(message(e));
            steps.save(ledger);
            failRun(run, message(e), step.id(), attempt);
            return null;
        }
    }

    private void failRun(WorkflowRun run, String message, String stepId, int attempt) {
        failures.save(new WorkflowRunFailure(run.getId(), stepId, run.getWorkflowId(), run.getEntity(),
                run.getSubjectId(), message, Math.max(attempt, 1)));
        if (run.alive()) {
            run.fail(message);
            runs.save(run);
        }
        log.error("[automation] run {} failed at step {}: {}", run.getId(), stepId, message);
    }

    // ----------------------------------------------------------- lifecycle

    /** Cancel one in-flight run; releases its slot in the active-run index. */
    public void cancel(UUID runId) {
        tx.executeWithoutResult(s ->
                runs.findById(runId).ifPresent(run -> {
                    if (run.alive()) {
                        run.cancel();
                        runs.save(run);
                        scheduled.deleteByRunId(runId);
                    }
                }));
    }

    /** Cancel every live run of a workflow (archive path, Module 1). */
    public void cancelForWorkflow(UUID workflowId) {
        tx.executeWithoutResult(s ->
                runs.findByWorkflowIdAndStatusIn(workflowId, java.util.Set.of(
                                WorkflowRunStatus.RUNNING, WorkflowRunStatus.WAITING))
                        .forEach(run -> {
                            run.cancel();
                            runs.save(run);
                            scheduled.deleteByRunId(run.getId());
                        }));
    }

    /**
     * Approve a parked {@code REQUEST_APPROVAL}: delete the approval schedule,
     * flip the run back to RUNNING and drive it forward. Non-transactional so
     * the resume loop does not run inside the approval's transaction.
     */
    public void approve(UUID runId) {
        WorkflowRun run = tx.execute(status -> {
            WorkflowRun r = runs.findById(runId).orElse(null);
            if (r == null || !r.alive()) {
                return r;
            }
            scheduled.findByRunId(runId).stream()
                    .filter(s -> s.getKind() == ScheduledStepKind.APPROVAL)
                    .findFirst()
                    .ifPresent(scheduled::delete);
            r.resume();
            runs.save(r);
            return r;
        });
        if (run != null && run.alive()) {
            coordinate(runId, run.getPosition());
        }
    }

    /**
     * Re-enter a run that has been parked (or left mid-flight by a crash) with
     * no schedule row attaching it. Used by the recovery sweep and the poller.
     */
    public void resumeAt(UUID runId, String position) {
        coordinate(runId, position);
    }

    /** The first step of the pinned definition (recovery entry point when the
     *  run's position has not been advanced yet). */
    public String firstStepId(UUID runId) {
        WorkflowRun run = runs.findById(runId).orElse(null);
        if (run == null) return null;
        WorkflowVersion version = versions.findById(run.getWorkflowVersionId()).orElse(null);
        if (version == null) return null;
        return parse(version.getDefinition()).steps().stream()
                .min(Comparator.comparingInt(StepDefinition::order))
                .map(StepDefinition::id)
                .orElse(null);
    }

    // -------------------------------------------------------------- internals

    private String advance(WorkflowDefinition def, StepDefinition step, RuntimeSnapshot snapshot) {
        if (step.action() == ActionType.BRANCH) {
            return branchTarget(def, step, snapshot);
        }
        return successor(def, step);
    }

    private String successor(WorkflowDefinition def, StepDefinition step) {
        return def.steps().stream()
                .filter(s -> s.order() > step.order())
                .min(Comparator.comparingInt(StepDefinition::order))
                .map(StepDefinition::id)
                .orElse(null);
    }

    private String branchTarget(WorkflowDefinition def, StepDefinition step, RuntimeSnapshot snapshot) {
        Object casesRaw = step.config().get("cases");
        if (casesRaw instanceof List<?> cases) {
            for (Object c : cases) {
                if (!(c instanceof Map<?, ?> caseMap)) continue;
                Object whenRaw = caseMap.get("when");
                if (whenRaw instanceof Map<?, ?> whenTree) {
                    ConditionNode when = mapper.convertValue(whenTree, ConditionNode.class);
                    if (evaluator.matches(when, snapshot)) {
                        Object gotoRaw = caseMap.get("goto");
                        return gotoRaw == null ? null : String.valueOf(gotoRaw);
                    }
                }
            }
        }
        Object defaultRaw = step.config().get("default");
        return defaultRaw == null ? null : String.valueOf(defaultRaw);
    }

    private WorkflowDefinition parse(String json) {
        try {
            return mapper.readValue(json, WorkflowDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("Pinned definition is invalid JSON", e);
        }
    }

    private int intConfig(Map<String, Object> config, String key, int fallback) {
        Object v = config.get(key);
        if (v == null) return fallback;
        return v instanceof Number n ? ((Number) n).intValue() : Integer.parseInt(String.valueOf(v));
    }

    private String strConfig(Map<String, Object> config, String key) {
        Object v = config.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private String message(Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}