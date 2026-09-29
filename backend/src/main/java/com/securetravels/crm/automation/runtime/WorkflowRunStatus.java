package com.securetravels.crm.automation.runtime;

/**
 * Lifecycle of a single workflow run (Phase 6 Module 2).
 *
 * <p>RUNNING executes steps right now; WAITING is parked on a
 * {@code workflow_scheduled_steps} row (WAIT, retry backoff or an awaiting
 * approval) and resumes via the poller or an explicit approval; SUCCEEDED,
 * FAILED and CANCELLED are terminal. At most one run per
 * (workflow, entity, subject) may sit in RUNNING/WAITING — the partial unique
 * index {@code uq_workflow_runs_active} enforces that.
 */
public enum WorkflowRunStatus {
    RUNNING, WAITING, SUCCEEDED, FAILED, CANCELLED
}