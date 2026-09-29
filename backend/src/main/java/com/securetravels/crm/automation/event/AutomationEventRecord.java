package com.securetravels.crm.automation.event;

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
 * The transactional outbox row behind {@link EventPublisher} (Phase 6 Module 2).
 *
 * <p>A domain service that raises an {@link AutomationEvent} records one of
 * these IN THE SAME TRANSACTION as the business change; the relay drains it
 * afterwards. {@code eventKey} is unique, so replaying the same publish (same
 * subject, same instant) is a no-op and the pipeline is exactly-once at the
 * trigger boundary. State is PENDING → PROCESSING → PROCESSED, with FAILED
 * (bounded attempts) for a drain that never completed.
 */
@Entity
@Table(name = "automation_events", indexes = {
        @Index(name = "idx_automation_events_claim", columnList = "status, created_at")
})
public class AutomationEventRecord extends CreatedUpdated {

    public enum Status { PENDING, PROCESSING, PROCESSED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String entity;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "event_key", nullable = false, length = 255)
    private String eventKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected AutomationEventRecord() {
    }

    public AutomationEventRecord(AutomationEvent event) {
        this(event.entity(), event.action(), event.subjectId(), event.occurredAt(), event.eventKey());
    }

    /** Trigger-swept events carry a window key ({@code ...:fire-minute}) so the
     *  unique {@code event_key} makes a repeated sweep of the same window a
     *  no-op, exactly like a replayed domain publish. */
    public AutomationEventRecord(String entity, String action, UUID subjectId,
                                 Instant occurredAt, String eventKey) {
        this.entity = entity;
        this.action = action;
        this.subjectId = subjectId;
        this.occurredAt = occurredAt;
        this.eventKey = eventKey;
    }

    public UUID getId() { return id; }
    public String getEntity() { return entity; }
    public String getAction() { return action; }
    public UUID getSubjectId() { return subjectId; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getEventKey() { return eventKey; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getProcessedAt() { return processedAt; }

    public void markProcessing() {
        this.status = Status.PROCESSING;
        this.attempts++;
    }

    public void markProcessed() {
        this.status = Status.PROCESSED;
        this.processedAt = Instant.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.status = Status.FAILED;
        this.lastError = error == null ? null
                : error.length() <= 4000 ? error : error.substring(0, 4000);
    }

    public AutomationEvent toEvent() {
        return new AutomationEvent(entity, action, subjectId, occurredAt);
    }
}