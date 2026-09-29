package com.securetravels.crm.automation.definition;

import java.util.List;
import java.util.Map;

/**
 * One step in a workflow definition (Phase 6 Module 1).
 *
 * <p>Steps run in ascending {@code order} unless a {@link ActionType#BRANCH}
 * redirects to a specific step id. {@code when} is an optional guard —
 * conditions evaluated just before the step runs; a false guard SKIPS the
 * step (recorded, never an error). {@code config} holds action-specific keys
 * validated by {@code WorkflowValidator} against the action's schema.
 *
 * @param id     unique within the definition, referenced by BRANCH targets.
 * @param order  execution order (unique within the definition).
 * @param action closed action vocabulary.
 * @param when   optional pre-step guard (null = always run).
 * @param config action parameters.
 * @param retry  retry budget (null = {@link RetryPolicy#NONE}).
 */
public record StepDefinition(
        String id,
        int order,
        ActionType action,
        ConditionNode when,
        Map<String, Object> config,
        RetryPolicy retry) {

    public StepDefinition {
        if (config == null) {
            config = Map.of();
        }
        if (retry == null) {
            retry = RetryPolicy.NONE;
        }
    }

    public List<String> branchTargets() {
        return action == ActionType.BRANCH ? BranchConfig.targets(config) : List.of();
    }
}