package com.securetravels.crm.automation;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.automation.definition.ActionType;
import com.securetravels.crm.automation.definition.RetryPolicy;
import com.securetravels.crm.automation.definition.StepDefinition;
import com.securetravels.crm.automation.definition.TriggerDefinition;
import com.securetravels.crm.automation.definition.TriggerKind;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.automation.event.AutomationEvent;
import com.securetravels.crm.automation.event.AutomationEventRecord;
import com.securetravels.crm.automation.event.AutomationEventRecordRepository;
import com.securetravels.crm.automation.event.DurableEventPublisher;
import com.securetravels.crm.automation.runtime.AutomationEventRelay;
import com.securetravels.crm.automation.runtime.RunRecoverySweep;
import com.securetravels.crm.automation.runtime.ScheduledStepKind;
import com.securetravels.crm.automation.runtime.TriggerSweep;
import com.securetravels.crm.automation.runtime.WorkflowRun;
import com.securetravels.crm.automation.runtime.WorkflowRunFailure;
import com.securetravels.crm.automation.runtime.WorkflowRunFailureRepository;
import com.securetravels.crm.automation.runtime.WorkflowRunRepository;
import com.securetravels.crm.automation.runtime.WorkflowRunService;
import com.securetravels.crm.automation.runtime.WorkflowRunStep;
import com.securetravels.crm.automation.runtime.WorkflowRunStepRepository;
import com.securetravels.crm.automation.runtime.WorkflowScheduledStepRepository;
import com.securetravels.crm.automation.runtime.WorkflowStepEffect;
import com.securetravels.crm.automation.runtime.WorkflowStepEffectRepository;
import com.securetravels.crm.automation.runtime.WorkflowStepPoller;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.communications.TimelineEventRepository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 2 — the workflow automation runtime. One real Postgres context (via
 * {@link BaseIT}); the relay, poller, trigger sweep and recovery sweep are all
 * driven manually because the test profile keeps their {@code @Scheduled}
 * periods dormant. Proves: outbox exactly-once and active-run coalescing,
 * per-step ledger + effect guard, WAIT durability through the poller,
 * retry-at-scale into the failure inbox, approval parking, cancellation and
 * archive-hook cancellation, recovery of a stranded run, and the
 * date-offset trigger sweep.
 */
class AutomationEngineIT extends BaseIT {

    @Autowired private WorkflowService workflows;
    @Autowired private DurableEventPublisher publisher;
    @Autowired private AutomationEventRelay relay;
    @Autowired private WorkflowStepPoller poller;
    @Autowired private RunRecoverySweep recovery;
    @Autowired private TriggerSweep triggerSweep;
    @Autowired private WorkflowRunService engine;

    @Autowired private AutomationEventRecordRepository eventRecords;
    @Autowired private WorkflowRunRepository runs;
    @Autowired private WorkflowRunStepRepository stepLedger;
    @Autowired private WorkflowScheduledStepRepository scheduled;
    @Autowired private WorkflowStepEffectRepository effects;
    @Autowired private WorkflowRunFailureRepository failures;
    @Autowired private LeadRepository leads;
    @Autowired private TaskRepository tasks;
    @Autowired private NotificationRepository notifications;
    @Autowired private TimelineEventRepository timeline;

    // ---------------------------------------------------------------------
    // 1. The happy path end-to-end
    // ---------------------------------------------------------------------

    @Test
    void eventTriggerDrivesMultiEffectWorkflowToCompletion() {
        UUID sales = newSales("happy");
        UUID leadId = seedLead(sales, LocalDate.now(ZoneOffset.UTC));
        UUID actor = newOps("happy");
        activate(actor, "speed", def(
                TriggerDefinition.event("lead.updated"),
                List.of(
                        new StepDefinition("assign", 1, ActionType.ASSIGN_OWNER, null,
                                Map.of("method", "ROUND_ROBIN"), null),
                        new StepDefinition("task", 2, ActionType.CREATE_TASK, null,
                                Map.of("type", "INITIAL_CALL", "assignee", "OWNER",
                                        "dueInMinutes", 60, "slaHours", 24), null),
                        new StepDefinition("notify", 3, ActionType.NOTIFY_USER, null,
                                Map.of("role", "SALES", "title", "New lead", "body", "Please follow up"), null),
                        new StepDefinition("date", 4, ActionType.UPDATE_FIELD, null,
                                Map.of("field", "followUpDate", "value", "today+1"), null),
                        new StepDefinition("send", 5, ActionType.SEND_MESSAGE, null,
                                Map.of("channel", "WHATSAPP", "templateCode", "BOOKING_CONFIRMED",
                                        "purpose", "TRANSACTIONAL", "to", "mobileNumber",
                                        "bodyValues", List.of("only-one-value")), null),
                        new StepDefinition("stop", 6, ActionType.STOP, null, Map.of(), null))));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();

        List<WorkflowRun> all = sortedRuns();
        assertThat(all).hasSize(1);
        WorkflowRun run = all.get(0);
        assertThat(run.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);
        assertThat(run.getFinishedAt()).isNotNull();
        assertThat(run.getEntity()).isEqualTo("lead");
        assertThat(run.getSubjectId()).isEqualTo(leadId);

        // The on-disk side effects really happened.
        assertThat(tasks.findAll()).hasSize(1);
        Task task = tasks.findAll().get(0);
        assertThat(task.getType()).isEqualTo(Task.Type.INITIAL_CALL);
        assertThat(task.getAssigneeId()).isEqualTo(sales);          // rotated via ROUND_ROBIN then OWNER
        assertThat(task.getLeadId()).isEqualTo(leadId);
        Lead fresh = leads.findById(leadId).orElseThrow();
        assertThat(fresh.getOwnerId()).isEqualTo(sales);
        assertThat(fresh.getFollowUpDate()).isEqualTo(LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThat(notifications.findAll()).extracting(n -> n.getUserId().toString())
                .contains(sales.toString());

        // The ledger records every step verdict in order.
        List<WorkflowRunStep> ledger = sortedLedger(run.getId());
        assertThat(ledger).extracting(WorkflowRunStep::getAction)
                .containsExactly("ASSIGN_OWNER", "CREATE_TASK", "NOTIFY_USER", "UPDATE_FIELD",
                        "SEND_MESSAGE", "STOP");
        assertThat(ledger).extracting(WorkflowRunStep::getStatus)
                .startsWith(com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SUCCEEDED,
                        com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SUCCEEDED,
                        com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SUCCEEDED,
                        com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SUCCEEDED)
                .endsWith(com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SUCCEEDED);
        WorkflowRunStep send = ledger.get(4);
        assertThat(send.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.SKIPPED);
        assertThat(send.getLastError()).contains("send gate declined");

        // A gate rejection is recorded as a timeline fact, and the gate was
        // genuinely reached (not short-circuited).
        assertThat(timeline.findAll().stream()
                .filter(e -> e.getKind() == TimelineEvent.Kind.SYSTEM_NOTE)
                .anyMatch(e -> e.getSummary() != null && e.getSummary().contains("Send gate"))).isTrue();

        // One effect row per executed attempt (incl. the SKIPPED send), and no
        // extra rows for structural steps.
        assertThat(effects.findAll()).hasSize(5);
        assertThat(effects.findAll()).extracting(WorkflowStepEffect::getEffect)
                .contains("ASSIGN_OWNER", "CREATE_TASK", "NOTIFY_USER", "UPDATE_FIELD", "SEND_MESSAGE");

        // The outbox row drained cleanly.
        AutomationEventRecord record = eventRecords.findAll().get(0);
        assertThat(record.getStatus()).isEqualTo(AutomationEventRecord.Status.PROCESSED);
    }

    // ---------------------------------------------------------------------
    // 2. Exactly-once at the trigger boundary
    // ---------------------------------------------------------------------

    @Test
    void replayingTheSamePublishNeverStartsARunTwice() {
        UUID sales = newSales("replay");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("replay");
        activate(actor, "replay", singleStop(TriggerDefinition.event("lead.updated")));

        AutomatedEvent event = new AutomatedEvent(leadId, Instant.now());
        publisher.publish(event.toEvent());
        publisher.publish(event.toEvent());            // replay: same instant, same subject
        relay.drain();

        assertThat(eventRecords.findAll()).hasSize(1); // emergent at the outbox unique key
        assertThat(runs.findAll()).hasSize(1);
        assertThat(runs.findAll().get(0).getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------------
    // 3. Matching triggers coalesce while a live run holds the subject
    // ---------------------------------------------------------------------

    @Test
    void matchingTriggersCoalesceWhileALiveRunExistsThenANewTriggerRuns() {
        UUID sales = newSales("coalesce");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("coalesce");
        activate(actor, "coalesce", waitThenStop(TriggerDefinition.event("lead.updated")));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        assertThat(runs.findAll()).hasSize(1);
        WorkflowRun first = runs.findAll().get(0);
        assertThat(first.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
        assertThat(scheduled.findByRunId(first.getId()))
                .extracting(s -> s.getKind()).containsExactly(ScheduledStepKind.WAIT);

        // A second trigger for the same live subject is coalesced, not started.
        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now().plusSeconds(5)));
        relay.drain();
        assertThat(runs.findAll()).hasSize(1);

        // The wait expires; the poller resumes and the run finishes.
        makeDue(first.getId());
        poller.drain();
        assertThat(scheduled.findByRunId(first.getId())).isEmpty();
        assertThat(runs.findById(first.getId()).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);

        // Once the slot is free, a fresh trigger starts a fresh run — which in
        // this workflow parks on the same WAIT step.
        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now().plusSeconds(60)));
        relay.drain();
        assertThat(runs.findAll()).hasSize(2);
        assertThat(sortedRuns().get(1).getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
    }

    // ---------------------------------------------------------------------
    // 4. Cancellation is honoured and survives a poller pass
    // ---------------------------------------------------------------------

    @Test
    void cancelledRunIsNeverResumedByThePoller() {
        UUID sales = newSales("cancel");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("cancel");
        activate(actor, "cancel", waitThenStop(TriggerDefinition.event("lead.updated")));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        UUID runId = runs.findAll().get(0).getId();
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);

        engine.cancel(runId);
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.CANCELLED);
        assertThat(scheduled.findByRunId(runId)).isEmpty();

        poller.drain();                              // must not resurrect the run
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.CANCELLED);
    }

    // ---------------------------------------------------------------------
    // 5. Archive cancels in-flight runs of that workflow
    // ---------------------------------------------------------------------

    @Test
    void archivingAWorkflowCancelsItsInFlightRuns() {
        UUID sales = newSales("archive");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("archive");
        activate(actor, "archive", waitThenStop(TriggerDefinition.event("lead.updated")));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        WorkflowRun run = runs.findAll().get(0);
        assertThat(run.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
        UUID runId = run.getId();

        workflows.archive(run.getWorkflowId(), actor);
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.CANCELLED);
        assertThat(scheduled.findByRunId(runId)).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 6. Retry budget exhausts into the failure inbox
    // ---------------------------------------------------------------------

    @Test
    void exhaustedRetryLandsInTheFailureInbox() {
        // No users at all: ROUND_ROBIN has nobody to assign to and throws.
        UUID leadId = seedLead(null, null);
        UUID actor = newOps("retry");
        activateRetried(actor, "retry",
                new StepDefinition("task", 1, ActionType.CREATE_TASK, null,
                        Map.of("type", "INITIAL_CALL", "assignee", "ROUND_ROBIN", "dueInMinutes", 5),
                        new RetryPolicy(2, 1)));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        WorkflowRun run = runs.findAll().get(0);
        assertThat(run.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
        assertThat(scheduled.findByRunId(run.getId()))
                .extracting(s -> s.getKind()).containsExactly(ScheduledStepKind.RETRY);

        makeDue(run.getId());
        poller.drain();

        WorkflowRun finalRun = runs.findById(run.getId()).orElseThrow();
        assertThat(finalRun.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.FAILED);
        assertThat(finalRun.getLastError()).isNotNull();

        assertThat(failures.findAll()).hasSize(1);
        WorkflowRunFailure failure = failures.findAll().get(0);
        assertThat(failure.getStatus()).isEqualTo(WorkflowRunFailure.Status.OPEN);
        assertThat(failure.getRunId()).isEqualTo(run.getId());
        assertThat(failure.getAttempts()).isEqualTo(2);
        assertThat(failure.getWorkflowId()).isNotNull();

        WorkflowRunStep ledgerRow = stepLedger.findByRunIdAndStepId(run.getId(), "task").orElseThrow();
        assertThat(ledgerRow.getAttempts()).isEqualTo(2);
        assertThat(ledgerRow.getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStepStatus.FAILED);
    }

    // ---------------------------------------------------------------------
    // 7. Approval parks the run until an operator decides
    // ---------------------------------------------------------------------

    @Test
    void approvalParksTheRunUntilAnOperatorApproves() {
        UUID manager = createUser("manager@securetravels.in", "Manager", Role.MANAGER, "P@ssw0rd");
        UUID sales = newSales("approval");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("approval");
        activate(actor, "approval", approvalThenStop(TriggerDefinition.event("lead.updated")));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        WorkflowRun run = runs.findAll().get(0);
        assertThat(run.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
        assertThat(scheduled.findByRunId(run.getId()))
                .extracting(s -> s.getKind()).containsExactly(ScheduledStepKind.APPROVAL);

        // The request raised a notification for the approver role.
        assertThat(notifications.findAll()).anyMatch(n -> n.getUserId().equals(manager));

        // The poller does NOT auto-resume an approval.
        poller.drain();
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);
        assertThat(scheduled.findByRunId(run.getId())).hasSize(1);

        engine.approve(run.getId());
        assertThat(runs.findById(run.getId()).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);
        assertThat(scheduled.findByRunId(run.getId())).isEmpty();
    }

    // ---------------------------------------------------------------------
    // 8. Date-offset sweep materialises window-keyed events, once
    // ---------------------------------------------------------------------

    @Test
    void dateOffsetTriggerFiresMatchingSubjectsOncePerWindow() {
        UUID sales = newSales("offset");
        UUID dueLead = seedLead(sales, LocalDate.now(ZoneOffset.UTC));            // fires today
        UUID futureLead = seedLead(sales, LocalDate.now(ZoneOffset.UTC).plusDays(1)); // not yet

        UUID actor = newOps("offset");
        activate(actor, "offset", singleStop(
                new TriggerDefinition(TriggerKind.DATE_OFFSET, null, "lead", null, "travelDate", 0)));

        triggerSweep.runOnce();
        relay.drain();
        assertThat(eventRecords.findAll()).hasSize(1);
        assertThat(runs.findAll()).hasSize(1);
        WorkflowRun run = runs.findAll().get(0);
        assertThat(run.getSubjectId()).isEqualTo(dueLead);
        assertThat(run.getStatus()).isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);

        // A second sweep of the same window is a no-op on the outbox key.
        triggerSweep.runOnce();
        relay.drain();
        assertThat(eventRecords.findAll()).hasSize(1);
        assertThat(runs.findAll()).hasSize(1);
    }

    // ---------------------------------------------------------------------
    // 9. Recovery re-enters a stranded run that owns no schedule row
    // ---------------------------------------------------------------------

    @Test
    void recoverySweepResumesARunStrandedBetweenPollerDeleteAndResume() {
        UUID sales = newSales("recover");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("recover");
        activate(actor, "recover", waitThenStop(TriggerDefinition.event("lead.updated")));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        UUID runId = runs.findAll().get(0).getId();
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.WAITING);

        // Simulate the crash window: the node claimed and deleted the schedule
        // row but died before resuming the run.
        jdbcTemplate.update("DELETE FROM workflow_scheduled_steps WHERE run_id = ?", runId);
        age(runId);

        recovery.runOnce();
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);
    }

    // ---------------------------------------------------------------------
    // 10. A terminal run is never re-entered; re-driving is idempotent
    // ---------------------------------------------------------------------

    @Test
    void reEnteringATerminalRunAppliesNothingTwice() {
        UUID sales = newSales("idempotent");
        UUID leadId = seedLead(sales, null);
        UUID actor = newOps("idempotent");
        activate(actor, "idempotent", def(
                TriggerDefinition.event("lead.updated"),
                List.of(
                        new StepDefinition("task", 1, ActionType.CREATE_TASK, null,
                                Map.of("type", "INITIAL_CALL", "assignee", "OWNER", "dueInMinutes", 5), null),
                        new StepDefinition("stop", 2, ActionType.STOP, null, Map.of(), null))));

        publisher.publish(new AutomationEvent("lead", "updated", leadId, Instant.now()));
        relay.drain();
        UUID runId = runs.findAll().get(0).getId();
        assertThat(runs.findById(runId).orElseThrow().getStatus())
                .isEqualTo(com.securetravels.crm.automation.runtime.WorkflowRunStatus.SUCCEEDED);
        assertThat(tasks.findAll()).hasSize(1);
        assertThat(effects.findAll()).hasSize(1);

        engine.resumeAt(runId, "task");             // stale re-entry after success
        assertThat(tasks.findAll()).hasSize(1);
        assertThat(effects.findAll()).hasSize(1);
    }

    // ------------------------------------------------------------------ helpers

    private WorkflowDefinition singleStop(TriggerDefinition trigger) {
        return new WorkflowDefinition(WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Stop only", null,
                trigger, null, List.of(new StepDefinition("stop", 1, ActionType.STOP, null, Map.of(), null)));
    }

    private WorkflowDefinition waitThenStop(TriggerDefinition trigger) {
        return new WorkflowDefinition(WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Wait then stop", null,
                trigger, null, List.of(
                        new StepDefinition("wait", 1, ActionType.WAIT, null, Map.of("minutes", 30), null),
                        new StepDefinition("stop", 2, ActionType.STOP, null, Map.of(), null)));
    }

    private WorkflowDefinition approvalThenStop(TriggerDefinition trigger) {
        return new WorkflowDefinition(WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Approval then stop", null,
                trigger, null, List.of(
                        new StepDefinition("approval", 1, ActionType.REQUEST_APPROVAL, null,
                                Map.of("role", "MANAGER", "message", "Approve the follow-up"), null),
                        new StepDefinition("stop", 2, ActionType.STOP, null, Map.of(), null)));
    }

    private WorkflowDefinition def(TriggerDefinition trigger, List<StepDefinition> steps) {
        return new WorkflowDefinition(WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Runtime", null,
                trigger, null, steps);
    }

    private WorkflowVersion activate(UUID actor, String slug, WorkflowDefinition definition) {
        UUID workflowId = workflows.createWorkflow(slug, "Workflow " + slug, null, actor).getId();
        workflows.saveDraft(workflowId, definition, actor);
        return workflows.activate(workflowId, actor);
    }

    private WorkflowVersion activateRetried(UUID actor, String slug, StepDefinition step) {
        return activate(actor, slug, new WorkflowDefinition(
                WorkflowDefinition.CURRENT_SCHEMA_VERSION, "Retry " + slug, null,
                TriggerDefinition.event("lead.updated"), null,
                List.of(step, new StepDefinition("stop", 2, ActionType.STOP, null, Map.of(), null))));
    }

    private UUID newOps(String slug) {
        return createUser("ops-" + slug + "@securetravels.in", "Ops", Role.OPS, "P@ssw0rd");
    }

    private UUID newSales(String slug) {
        return createUser("sales-" + slug + "@securetravels.in", "Sales", Role.SALES, "P@ssw0rd");
    }

    private UUID seedLead(UUID ownerId, LocalDate travelDate) {
        Lead lead = new Lead();
        lead.setCustomerName("Lead " + UUID.randomUUID().toString().substring(0, 8));
        lead.setMobileNumber("919876" + (10000 + (int) (Math.random() * 89999)));
        lead.setMobileDigits("9");
        lead.setSource(Lead.Source.WEBSITE);
        lead.setStatus(Lead.Status.INTERESTED);
        lead.setHeat(Lead.Heat.HOT);
        lead.setOwnerId(ownerId);
        lead.setTravelDate(travelDate);
        lead.setConsentGiven(true);
        lead.setCreatedBy(ownerId);
        return leads.save(lead).getId();
    }

    private List<WorkflowRun> sortedRuns() {
        return runs.findAll().stream()
                .sorted(Comparator.comparing(WorkflowRun::getStartedAt))
                .toList();
    }

    private List<WorkflowRunStep> sortedLedger(UUID runId) {
        return stepLedger.findAll().stream()
                .filter(s -> s.getRunId().equals(runId))
                .sorted(Comparator.comparingInt(WorkflowRunStep::getStepOrder))
                .toList();
    }

    private void makeDue(UUID runId) {
        jdbcTemplate.update("UPDATE workflow_scheduled_steps SET run_after = now() - interval '1 minute' WHERE run_id = ?", runId);
    }

    private void age(UUID runId) {
        jdbcTemplate.update("UPDATE workflow_runs SET updated_at = now() - interval '2 hours' WHERE id = ?", runId);
    }

    /** Named to avoid clashing with the incoming {@code AutomationEvent}. */
    private record AutomatedEvent(UUID leadId, Instant occurredAt) {
        AutomationEvent toEvent() {
            return new AutomationEvent("lead", "updated", leadId, occurredAt);
        }
    }
}