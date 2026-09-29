package com.securetravels.crm.automation.domain;

/**
 * Lifecycle of a workflow version.
 *
 * <p>Status lives on the <em>version</em>, not the workflow: editing an ACTIVE
 * workflow creates a new DRAFT version rather than mutating the running one,
 * so in-flight runs (pinned to the version they started from) are never
 * changed underneath. At most one version per workflow can be ACTIVE
 * {@code (uq_workflow_versions_active)}.
 */
public enum WorkflowStatus {
    /** Being written; not runnable. */
    DRAFT,
    /** Runnable: the engine matches triggers against ACTIVE definitions. */
    ACTIVE,
    /** Manually halted (superior control exercised per Module 3 guardrails). */
    PAUSED,
    /** Superseded; kept for history, never runnable. */
    ARCHIVED
}