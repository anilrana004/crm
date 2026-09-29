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
 * One ledger row per step of a run (Phase 6 Module 2).
 *
 * <p>Written as the step executes; {@code status} SUCCEEDED/SKIPPED is the
 * exactly-once guard (a re-entered run skips such steps without re-running
 * them). {@code attempts} counts executions, {@code lastError} carries the
 * most recent failure (or the skip reason).
 */
@Entity
@Table(name = "workflow_run_steps", indexes = {
        @Index(name = "idx_workflow_run_steps_status", columnList = "run_id, status")
})
public class WorkflowRunStep extends CreatedUpdated {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(nullable = false, length = 30)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowRunStepStatus status = WorkflowRunStepStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "executed_at")
    private Instant executedAt;

    protected WorkflowRunStep() {
    }

    public WorkflowRunStep(UUID runId, String stepId, int stepOrder, String action, int attempts) {
        this.runId = runId;
        this.stepId = stepId;
        this.stepOrder = stepOrder;
        this.action = action;
        this.attempts = attempts;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public int getStepOrder() { return stepOrder; }
    public String getAction() { return action; }
    public WorkflowRunStepStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getExecutedAt() { return executedAt; }

    public boolean finished() {
        return status == WorkflowRunStepStatus.SUCCEEDED || status == WorkflowRunStepStatus.SKIPPED;
    }

    public void attempt(int attempts) {
        this.attempts = attempts;
        this.status = WorkflowRunStepStatus.PENDING;
    }

    public void succeeded() {
        this.status = WorkflowRunStepStatus.SUCCEEDED;
        this.executedAt = Instant.now();
        this.lastError = null;
    }

    public void skipped(String reason) {
        this.status = WorkflowRunStepStatus.SKIPPED;
        this.executedAt = Instant.now();
        this.lastError = truncate(reason);
    }

    public void failed(String error) {
        this.status = WorkflowRunStepStatus.FAILED;
        this.executedAt = Instant.now();
        this.lastError = truncate(error);
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 4000 ? error : error.substring(0, 4000);
    }
}