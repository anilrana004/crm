package com.securetravels.crm.automation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.automation.definition.WorkflowDefinition;
import com.securetravels.crm.automation.domain.Workflow;
import com.securetravels.crm.automation.domain.WorkflowRepository;
import com.securetravels.crm.automation.domain.WorkflowStatus;
import com.securetravels.crm.automation.domain.WorkflowVersion;
import com.securetravels.crm.automation.domain.WorkflowVersionRepository;
import com.securetravels.crm.automation.validation.ValidationResult;
import com.securetravels.crm.automation.validation.WorkflowValidator;
import com.securetravels.crm.automation.runtime.WorkflowRunService;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Workflow lifecycle (Phase 6 Module 1).
 *
 * <p>State machine per workflow:
 * <pre>
 *   create → (DRAFT) → active → paused ⇄ active → archived
 *                  ↖        active  →  new DRAFT (edit) → active (old → PAUSED)
 * </pre>
 * A workflow has at most one ACTIVE version (partial unique index in V17).
 * Publishing a new version pauses the currently ACTIVE one so that in-flight
 * runs keep their pinned snapshot while the new behaviour comes live. Drafts
 * are edited in place (same version number) but are never what a run pins.
 *
 * <p>Every mutation goes through {@link WorkflowValidator} and is audited.
 * {@link #saveDraft} establishes a definition that may still be edited;
 * {@link #activate} re-validates the stored document before it goes live, so
 * nothing hostile can sneak in between editing and publishing.
 */
@Service
public class WorkflowService {

    private static final String ENTITY = "workflow";

    private final WorkflowRepository workflows;
    private final WorkflowVersionRepository versions;
    private final WorkflowValidator validator;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final WorkflowRunService runs;

    public WorkflowService(WorkflowRepository workflows, WorkflowVersionRepository versions,
                           WorkflowValidator validator, ObjectMapper objectMapper,
                           AuditService audit, WorkflowRunService runs) {
        this.workflows = workflows;
        this.versions = versions;
        this.validator = validator;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.runs = runs;
    }

    // ------------------------------------------------------------ lifecycle

    @Transactional
    public Workflow createWorkflow(String slug, String name, String description, UUID createdBy) {
        if (workflows.findBySlug(slug).isPresent()) {
            throw new WorkflowStateException("Workflow slug already in use: " + slug);
        }
        Workflow saved = workflows.save(new Workflow(slug, name, description, createdBy));
        audit.record(ENTITY, saved.getId(), AuditAction.CREATE, "workflow", null, slug);
        return saved;
    }

    /**
     * Persist a definition as the workflow's current DRAFT. Re-validates every
     * time (the document is data, and the validator is the only authority on
     * what is legal), then writes a new DRAFT row if none exists or replaces
     * the existing DRAFT in place.
     */
    @Transactional
    public WorkflowVersion saveDraft(UUID workflowId, WorkflowDefinition definition, UUID actorId) {
        Workflow workflow = requireWorkflow(workflowId);
        ValidationResult validation = validator.validate(definition);
        if (!validation.isValid()) {
            throw new InvalidWorkflowDefinitionException(workflow.getSlug(), validation);
        }
        WorkflowVersion draft = currentDraft(workflowId).orElseGet(() ->
                new WorkflowVersion(workflowId, nextVersionNumber(workflowId), json(definition)));
        if (draft.getStatus() != WorkflowStatus.DRAFT) {
            throw new WorkflowStateException("Workflow '" + workflow.getSlug()
                    + "' has no DRAFT version to edit (publish or archive the open one)");
        }
        draft.definition(json(definition));
        WorkflowVersion saved = versions.save(draft);
        audit.record(ENTITY, workflowId, AuditAction.UPDATE,
                "workflow_version." + saved.getVersionNumber(), null, saved.getStatus().name());
        return saved;
    }

    /**
     * Publish the current DRAFT: re-validate the stored document, flip the
     * previous ACTIVE version (if any) to PAUSED, then activate this one.
     */
    @Transactional
    public WorkflowVersion activate(UUID workflowId, UUID actorId) {
        Workflow workflow = requireWorkflow(workflowId);
        WorkflowVersion draft = currentDraft(workflowId).orElseThrow(() ->
                new WorkflowStateException("Workflow '" + workflow.getSlug()
                        + "' has no DRAFT version to activate"));
        ValidationResult validation = validator.validate(parse(draft.getDefinition()));
        if (!validation.isValid()) {
            throw new InvalidWorkflowDefinitionException(workflow.getSlug(), validation);
        }
        activeVersion(workflowId).ifPresent(previous -> {
            previous.transition(WorkflowStatus.PAUSED, actorId);
            // Flush-first: the partial unique index (uq_workflow_versions_active)
            // forbids two ACTIVE rows, so the retire must hit the database before
            // the new ACTIVE update or Hibernate's arbitrary ordering would trip it.
            versions.saveAndFlush(previous);
        });
        draft.transition(WorkflowStatus.ACTIVE, actorId);
        WorkflowVersion saved = versions.save(draft);
        audit.statusChange(ENTITY, workflowId, "workflow_version.status",
                "DRAFT", "ACTIVE v" + saved.getVersionNumber());
        return saved;
    }

    @Transactional
    public WorkflowVersion pause(UUID workflowId, UUID actorId) {
        WorkflowVersion active = activeVersion(workflowId).orElseThrow(() ->
                new WorkflowStateException("Workflow has no ACTIVE version to pause"));
        active.transition(WorkflowStatus.PAUSED, actorId);
        WorkflowVersion saved = versions.save(active);
        audit.statusChange(ENTITY, workflowId, "workflow_version.status",
                "ACTIVE v" + saved.getVersionNumber(), "PAUSED");
        return saved;
    }

    @Transactional
    public WorkflowVersion resume(UUID workflowId, UUID actorId) {
        Optional<WorkflowVersion> paused = versions.findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(
                workflowId, WorkflowStatus.PAUSED);
        WorkflowVersion version = paused.orElseThrow(() ->
                new WorkflowStateException("Workflow has no PAUSED version to resume"));
        version.transition(WorkflowStatus.ACTIVE, actorId);
        WorkflowVersion saved = versions.save(version);
        audit.statusChange(ENTITY, workflowId, "workflow_version.status",
                "PAUSED", "ACTIVE v" + saved.getVersionNumber());
        return saved;
    }

    /** Archive the workflow and every version row, releasing any ACTIVE pin. */
    @Transactional
    public Workflow archive(UUID workflowId, UUID actorId) {
        Workflow workflow = requireWorkflow(workflowId);
        versions.findByWorkflowIdOrderByVersionNumberDesc(workflowId).forEach(version -> {
            if (version.getStatus() != WorkflowStatus.ARCHIVED) {
                version.transition(WorkflowStatus.ARCHIVED, actorId);
                versions.save(version);
            }
        });
        // Release the in-flight slot: any RUNNING/WAITING run of this workflow
        // is over (cancelled), and its pinned version is now ARCHIVED so even
        // a racing recovery sweep would refuse to resume it.
        runs.cancelForWorkflow(workflowId);
        audit.statusChange(ENTITY, workflowId, "workflow.status", null, "ARCHIVED");
        return workflow;
    }

    // ------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Optional<Workflow> findBySlug(String slug) {
        return workflows.findBySlug(slug);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowVersion> activeVersion(UUID workflowId) {
        return versions.findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(
                workflowId, WorkflowStatus.ACTIVE);
    }

    @Transactional(readOnly = true)
    public Optional<WorkflowVersion> currentDraft(UUID workflowId) {
        return versions.findFirstByWorkflowIdAndStatusOrderByVersionNumberDesc(
                workflowId, WorkflowStatus.DRAFT);
    }

    /** Every version of one workflow, newest first (draft/edit history view). */
    @Transactional(readOnly = true)
    public List<WorkflowVersion> history(UUID workflowId) {
        return versions.findByWorkflowIdOrderByVersionNumberDesc(workflowId);
    }

    /** The engine's feed (Module 2 subscribes here; newest per workflow). */
    @Transactional(readOnly = true)
    public List<WorkflowVersion> activeDefinitions() {
        return versions.findByStatusOrderByCreatedAtAsc(WorkflowStatus.ACTIVE);
    }

    // -------------------------------------------------------------- internals

    private Workflow requireWorkflow(UUID workflowId) {
        return workflows.findById(workflowId).orElseThrow(() ->
                new WorkflowStateException("Unknown workflow id " + workflowId));
    }

    private int nextVersionNumber(UUID workflowId) {
        return versions.findByWorkflowIdOrderByVersionNumberDesc(workflowId).stream()
                .map(WorkflowVersion::getVersionNumber)
                .max(Integer::compareTo)
                .map(n -> n + 1)
                .orElse(1);
    }

    private WorkflowDefinition parse(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDefinition.class);
        } catch (JsonProcessingException e) {
            throw new WorkflowStateException("Stored definition is not valid JSON: " + e.getMessage());
        }
    }

    private String json(WorkflowDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Definition serialization failed", e);
        }
    }
}