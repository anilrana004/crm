package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A triggered execution of a workflow version against one subject
 * (Phase 6 Module 2).
 *
 * <p>Pins the {@code workflow_version} it started from (definitions are
 * append-only), the subject, and the trigger it came from. {@code eventKey} is
 * unique per workflow so a replayed trigger cannot double-start the same run,
 * and the partial index over {@code (workflow_id, entity, subject_id)} keeps
 * at most one live run per subject — matching triggers coalesce.
 */
@Entity
@Table(name = "workflow_runs")
public class WorkflowRun extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "workflow_version_id", nullable = false)
    private UUID workflowVersionId;

    @Column(nullable = false, length = 30)
    private String entity;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "trigger_event", nullable = false, length = 100)
    private String triggerEvent;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WorkflowRunStatus status = WorkflowRunStatus.RUNNING;

    /** The step the run is parked on / last worked on; the recovery sweep
     *  re-enters a stuck run here (null = none). */
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(length = 64)
    private String position;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected WorkflowRun() {
    }

    public WorkflowRun(UUID workflowId, UUID workflowVersionId, String entity, UUID subjectId,
                       String triggerEvent, String eventKey) {
        this.workflowId = workflowId;
        this.workflowVersionId = workflowVersionId;
        this.entity = entity;
        this.subjectId = subjectId;
        this.triggerEvent = triggerEvent;
        this.eventKey = eventKey;
    }

    public UUID getId() { return id; }
    public UUID getWorkflowId() { return workflowId; }
    public UUID getWorkflowVersionId() { return workflowVersionId; }
    public String getEntity() { return entity; }
    public UUID getSubjectId() { return subjectId; }
    public String getTriggerEvent() { return triggerEvent; }
    public String getEventKey() { return eventKey; }
    public WorkflowRunStatus getStatus() { return status; }
    public String getPosition() { return position; }
    public String getLastError() { return lastError; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }

    public void position(String position) { this.position = position; }

    public boolean alive() {
        return status == WorkflowRunStatus.RUNNING || status == WorkflowRunStatus.WAITING;
    }

    public void park() {
        this.status = WorkflowRunStatus.WAITING;
    }

    public void resume() {
        this.status = WorkflowRunStatus.RUNNING;
    }

    public void succeed() {
        this.status = WorkflowRunStatus.SUCCEEDED;
        this.finishedAt = Instant.now();
        this.lastError = null;
        this.position = null;
    }

    public void fail(String error) {
        this.status = WorkflowRunStatus.FAILED;
        this.finishedAt = Instant.now();
        this.lastError = truncate(error);
        this.position = null;
    }

    public void cancel() {
        this.status = WorkflowRunStatus.CANCELLED;
        this.finishedAt = Instant.now();
        this.lastError = null;
        this.position = null;
    }

    private static String truncate(String error) {
        if (error == null) return null;
        return error.length() <= 4000 ? error : error.substring(0, 4000);
    }
}