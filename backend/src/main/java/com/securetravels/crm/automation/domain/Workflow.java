package com.securetravels.crm.automation.domain;

import com.securetravels.crm.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * The stable identity of a workflow rule (Phase 6 Module 1).
 *
 * <p>A workflow is deliberately featureless: everything that changes behaviour
 * lives in {@link WorkflowVersion}. This row only answers "what rules exist"
 * and carries the human-facing name/slug so a definition can be found,
 * re-opened as a new draft, and audited by slug.
 */
@Entity
@Table(name = "workflows")
public class Workflow extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "slug", nullable = false, length = 100, unique = true)
    private String slug;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "created_by")
    private UUID createdBy;

    protected Workflow() {
    }

    public Workflow(String slug, String name, String description, UUID createdBy) {
        this.slug = slug;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public UUID getCreatedBy() { return createdBy; }
}