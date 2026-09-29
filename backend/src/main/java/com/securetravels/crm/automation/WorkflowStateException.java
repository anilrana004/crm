package com.securetravels.crm.automation;

/**
 * Illegal lifecycle transition: activating without a draft, pausing a workflow
 * that is not running, re-activating under a conflicting version, editing when
 * there is no draft, and so on. Distinct from {@link InvalidWorkflowDefinitionException}
 * (content vs state).
 */
public class WorkflowStateException extends RuntimeException {

    public WorkflowStateException(String message) {
        super(message);
    }
}