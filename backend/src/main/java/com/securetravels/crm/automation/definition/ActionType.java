package com.securetravels.crm.automation.definition;

/**
 * The closed action vocabulary (Phase 6 Module 1). A workflow step executes
 * exactly one of these. New actions are added by code + review, never by
 * configuration, so the executor only ever switches on this enum.
 */
public enum ActionType {
    /** Materialise a {@code tasks} row (due offset, SLA, assignee resolution). */
    CREATE_TASK,
    /** Assign/rotate ownership of the subject (round-robin cursor or a specific user). */
    ASSIGN_OWNER,
    /** Write one allow-listed field on the subject entity. */
    UPDATE_FIELD,
    /** Send a message via the Phase 5 send-gate — never a provider directly. */
    SEND_MESSAGE,
    /** Bell + email a role or user. */
    NOTIFY_USER,
    /** Open an approval request to a role (blocks the run until decided). */
    REQUEST_APPROVAL,
    /** Add a remarketing tag to the subject's customer record. */
    ADD_TAG,
    /** Enrol the subject into a Phase 5 nurture sequence. */
    ENROLL_SEQUENCE,
    /** Durable pause before continuing (Module 2 scheduled-step poller). */
    WAIT,
    /** Exclusive branch to another step by id. */
    BRANCH,
    /** Outbound webhook call (HTTPS + allow-list; Module 3 guardrails). */
    CALL_WEBHOOK,
    /** Terminate the run as a success. */
    STOP
}