package com.securetravels.crm.automation.validation;

import java.util.ArrayList;
import java.util.List;

/** Collected issues for one definition; {@value #isValid} gates activation. */
public final class ValidationResult {

    private final List<ValidationIssue> issues = new ArrayList<>();

    public void add(ValidationIssue issue) {
        issues.add(issue);
    }

    public void addAll(List<ValidationIssue> more) {
        issues.addAll(more);
    }

    public boolean isValid() {
        return issues.stream().noneMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public List<ValidationIssue> issues() {
        return List.copyOf(issues);
    }

    public List<ValidationIssue> errors() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.ERROR).toList();
    }

    public List<ValidationIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == ValidationIssue.Severity.WARNING).toList();
    }

    public boolean hasIssues() {
        return !issues.isEmpty();
    }
}