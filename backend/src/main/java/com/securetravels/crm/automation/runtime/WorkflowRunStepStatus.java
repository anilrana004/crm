package com.securetravels.crm.automation.runtime;

/**
 * Per-step ledger state (Phase 6 Module 2).
 *
 * <p>A step is PENDING until it has an outcome: SUCCEEDED (effect applied or
 * no-op), SKIPPED (its {@code when} guard was false, or the send-gate declined
 * with a reason — recorded, never an error), or FAILED (threw and either
 * retried out or exhausted its budget). SUCCEEDED/SKIPPED steps are never
 * re-run, which is what makes the run re-entrant and exactly-once.
 */
public enum WorkflowRunStepStatus {
    PENDING, SUCCEEDED, SKIPPED, FAILED
}