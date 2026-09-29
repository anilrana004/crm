package com.securetravels.crm.automation.runtime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The effect outbox for one step attempt (Phase 6 Module 2).
 *
 * <p>Inserted IN THE SAME TRANSACTION as the side effect it records, keyed by
 * {@code (run_id, step_id, attempt_group)}. A re-entered execution of the same
 * attempt (crash recovery, a concurrent poller racing a slow step) hits the
 * unique key and concludes the effect is already applied, so it never applies
 * it twice — this is the mechanism that makes exactly-once replay hold
 * regardless of what the underlying domain feature can and cannot promise.
 */
@Entity
@Table(name = "workflow_step_effects", indexes = {
        @Index(name = "uq_step_effects_attempt", columnList = "run_id, step_id, attempt_group", unique = true)
})
public class WorkflowStepEffect {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "run_id", nullable = false)
    private UUID runId;

    @Column(name = "step_id", nullable = false, length = 64)
    private String stepId;

    @Column(name = "attempt_group", nullable = false)
    private int attemptGroup;

    @Column(nullable = false, length = 30)
    private String effect;

    @Column(name = "ref_id")
    private UUID refId;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WorkflowStepEffect() {
    }

    public WorkflowStepEffect(UUID runId, String stepId, int attemptGroup,
                              String effect, UUID refId, String summary) {
        this.runId = runId;
        this.stepId = stepId;
        this.attemptGroup = attemptGroup;
        this.effect = effect;
        this.refId = refId;
        this.summary = summary;
    }

    public UUID getId() { return id; }
    public UUID getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public int getAttemptGroup() { return attemptGroup; }
    public String getEffect() { return effect; }
    public UUID getRefId() { return refId; }
    public String getSummary() { return summary; }
    public Instant getCreatedAt() { return createdAt; }

    /** Filled in after the side effect succeeds (same transaction). */
    public void refId(UUID refId) { this.refId = refId; }
    public void summary(String summary) { this.summary = summary; }
}