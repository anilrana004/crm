package com.securetravels.crm.automation;

import com.securetravels.crm.automation.validation.ValidationResult;

/**
 * Thrown when a definition fails {@code WorkflowValidator}. Carries the
 * collected issues so a controller can return them to the editor (Module 5 UI)
 * intact instead of serialising a single message.
 */
public class InvalidWorkflowDefinitionException extends RuntimeException {

    private final String slug;
    private final ValidationResult validation;

    public InvalidWorkflowDefinitionException(String slug, ValidationResult validation) {
        super("Workflow '" + slug + "' definition is invalid");
        this.slug = slug;
        this.validation = validation;
    }

    public String getSlug() {
        return slug;
    }

    public ValidationResult getValidation() {
        return validation;
    }
}