package com.securetravels.crm.automation.validation;

/**
 * A single validation finding. Severity distinguishes hard rejection
 * ({@code ERROR}) from information the operator should see but that does not
 * block activation ({@code WARNING}).
 *
 * @param severity ERROR or WARNING.
 * @param message  human-readable, actionable description.
 * @param stepId   step the issue belongs to, when applicable (path for the UI).
 */
public record ValidationIssue(Severity severity, String message, String stepId) {

    public enum Severity { ERROR, WARNING }

    public static ValidationIssue error(String message) {
        return new ValidationIssue(Severity.ERROR, message, null);
    }

    public static ValidationIssue error(String message, String stepId) {
        return new ValidationIssue(Severity.ERROR, message, stepId);
    }

    public static ValidationIssue warning(String message, String stepId) {
        return new ValidationIssue(Severity.WARNING, message, stepId);
    }
}