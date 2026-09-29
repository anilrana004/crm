package com.securetravels.crm.automation.definition;

import java.util.List;
import java.util.Map;

/**
 * The full workflow definition document, stored verbatim as {@code jsonb} on
 * {@code WorkflowVersion}.
 *
 * <p>Shape: one trigger; an optional entry-condition tree over the trigger's
 * entity; an ordered, possibly branching list of steps. The document is
 * validated as a whole by {@code WorkflowValidator} (closed vocabulary, no
 * scriptable expressions, no dangling branches, bounded size) before the
 * version that carries it may be activated.
 *
 * @param schemaVersion    fixed at 1; a future breaking shape change bumps it.
 * @param name             human name (<= 200 chars).
 * @param description      optional (<= 1000 chars).
 * @param trigger          the trigger declaration.
 * @param entryConditions  optional tree evaluated against the subject before
 *                         the run starts; null = always enter.
 * @param steps            ordered steps; must contain at least one and no more
 *                         than the configured maximum (default 30).
 */
public record WorkflowDefinition(
        int schemaVersion,
        String name,
        String description,
        TriggerDefinition trigger,
        ConditionNode entryConditions,
        List<StepDefinition> steps) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public WorkflowDefinition {
        if (steps == null) {
            steps = List.of();
        }
    }

    public StepDefinition step(String id) {
        return steps.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
    }

    /** Convenience factory: a single-action workflow with no guard. */
    public static WorkflowDefinition simple(String name, TriggerDefinition trigger,
                                            List<StepDefinition> steps) {
        return new WorkflowDefinition(CURRENT_SCHEMA_VERSION, name, null, trigger, null, steps);
    }
}