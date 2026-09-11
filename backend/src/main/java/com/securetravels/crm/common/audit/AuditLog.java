package com.securetravels.crm.common.audit;

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
 * Requirement 9: who changed what, from which value to which value, and when.
 * Every lead-status change and payment-status update writes a row here.
 */
@Entity
@Table(name = "audit_log", indexes = {
        @Index(name = "idx_audit_entity", columnList = "entity, entity_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 80)
    private String entity;

    @Column(name = "entity_id", nullable = false)
    private UUID entityId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AuditAction action;

    @Column(length = 80)
    private String field;

    @Column(name = "old_value", columnDefinition = "text")
    private String oldValue;

    @Column(name = "new_value", columnDefinition = "text")
    private String newValue;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Monotonic insertion order (unique via bigserial); drives activity timelines. */
    @Column(name = "seq", insertable = false, updatable = false)
    private Long seq;

    protected AuditLog() {}

    public AuditLog(String entity, UUID entityId, AuditAction action, String field,
                    String oldValue, String newValue, UUID actorId) {
        this.entity = entity;
        this.entityId = entityId;
        this.action = action;
        this.field = field;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.actorId = actorId;
    }

    public UUID getId() { return id; }
    public String getEntity() { return entity; }
    public UUID getEntityId() { return entityId; }
    public AuditAction getAction() { return action; }
    public String getField() { return field; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public UUID getActorId() { return actorId; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getSeq() { return seq; }
}