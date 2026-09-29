package com.securetravels.crm.automation.domain;

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
 * An immutable definition snapshot of a workflow (Phase 6 Module 1).
 *
 * <p>{@code definition} is the full trigger → condition → steps document
 * (see {@code automation.definition}) stored as {@code jsonb}. A version is
 * write-once data: DRAFTs are replaced by new rows, ACTIVE is pinned by
 * in-flight runs, and PAUSED/ARCHIVED retain exactly what ran so history stays
 * reproducible.
 *
 * <p>Rich JSON typing is deliberately absent: the definition is a closed set of
 * records (no polymorphic Javaclass keys), so deserialising untrusted JSON
 * cannot reach arbitrary classes. {@code WorkflowValidator} enforces the
 * closed vocabulary before a version may be activated.
 */
@Entity
@Table(name = "workflow_versions")
public class WorkflowVersion extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkflowStatus status = WorkflowStatus.DRAFT;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "definition", nullable = false, columnDefinition = "jsonb")
    private String definition;

    @Column(name = "published_by")
    private UUID publishedBy;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkflowVersion() {
    }

    public WorkflowVersion(UUID workflowId, int versionNumber, String definition) {
        this.workflowId = workflowId;
        this.versionNumber = versionNumber;
        this.definition = definition;
    }

    public UUID getId() { return id; }
    public UUID getWorkflowId() { return workflowId; }
    public int getVersionNumber() { return versionNumber; }
    public WorkflowStatus getStatus() { return status; }
    public String getDefinition() { return definition; }
    public UUID getPublishedBy() { return publishedBy; }
    public Instant getPublishedAt() { return publishedAt; }

    /** DRAFT rows are edited in place (same version number); once ACTIVE a
     *  version becomes immutable and a new DRAFT is opened instead. */
    public void definition(String definition) {
        if (status != WorkflowStatus.DRAFT) {
            throw new IllegalStateException("Cannot edit a " + status + " workflow version");
        }
        this.definition = definition;
    }

    /** Activation side effects (published_at/by) belong to the state machine;
     *  the status transition itself is the only thing this mutates. */
    public void transition(WorkflowStatus next, UUID by) {
        this.status = next;
        if (next == WorkflowStatus.ACTIVE) {
            this.publishedBy = by;
            this.publishedAt = Instant.now();
        }
    }
}