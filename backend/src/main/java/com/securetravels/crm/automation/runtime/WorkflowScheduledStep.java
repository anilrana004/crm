package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.common.audit.CreatedUpdated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A durable parking row for a run (Phase 6 Module 2).
 *
 * <p>WAIT rows are created by the {@code WAIT} action and reclaimed by the
 * poller once {@code runAfter} passes; RETRY rows are the backoff for a failed
 * step; APPROVAL rows park a run awaiting a human decision and are resolved
 * via the approval API, not the poller. The poller claims rows with
 * {@code FOR UPDATE SKIP LOCKED} so concurrent pollers never double-execute.
 */
@Entity
@Table(name = "workflow_scheduled_steps", indexes = {
        @Index(name = "idx_scheduled_steps_due", columnList = "run_after, kind")
})
public class WorkflowScheduledStep extends CreatedUpdated {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ScheduledStepKind kind;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter;

    protected WorkflowScheduledStep() {
    }

    public WorkflowScheduledStep(UUID runId, String stepId, ScheduledStepKind kind, Instant runAfter) {
        this.runId = runId;
        this.stepId = stepId;
        this.kind = kind;
        this.runAfter = runAfter;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public ScheduledStepKind getKind() { return kind; }
    public Instant getRunAfter() { return runAfter; }
}