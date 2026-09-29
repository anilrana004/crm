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

import java.util.UUID;

/**
 * The failure inbox (Phase 6 Module 2).
 *
 * <p>When a run exhausts its retry budget the engine writes one OPEN row here,
 * so "workflow X failed for subject Y at step Z" is a queryable fact for the
 * Manager inbox (Module 5) and for the ops JIRA, not a log line. Rows are
 * resolved by an operator; a resolved row is not re-raised.
 */
@Entity
@Table(name = "workflow_run_failures", indexes = {
        @Index(name = "idx_run_failures_open", columnList = "status, created_at")
})
public class WorkflowRunFailure extends CreatedUpdated {

    public enum Status { OPEN, RESOLVED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_id", length = 64)
    private String stepId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(nullable = false, length = 30)
    private String entity;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(nullable = false, columnDefinition = "text")
    private String error;

    @Column(nullable = false)
    private int attempts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    protected WorkflowRunFailure() {
    }

    public WorkflowRunFailure(UUID runId, String stepId, UUID workflowId, String entity,
                              UUID subjectId, String error, int attempts) {
        this.runId = runId;
        this.stepId = stepId;
        this.workflowId = workflowId;
        this.entity = entity;
        this.subjectId = subjectId;
        this.error = error;
        this.attempts = attempts;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public UUID getWorkflowId() { return workflowId; }
    public String getEntity() { return entity; }
    public UUID getSubjectId() { return subjectId; }
    public String getError() { return error; }
    public int getAttempts() { return attempts; }
    public Status getStatus() { return status; }

    public void resolve() {
        this.status = Status.RESOLVED;
    }
}