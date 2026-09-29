package com.securetravels.crm.automation.definition;

/**
 * A single leaf comparison on a field of the trigger's entity.
 *
 * @param field a dotted field name that must exist in the field registry for
 *              the trigger entity (allow-list — anything else, including SpEL
 *              / OGNL / EL / T(...) strings, is rejected at validation).
 * @param op    comparison operator.
 * @param value literal value (absent/null for {@code IS_NULL}/{@code NOT_NULL};
 *              a list for {@code IN}/{@code NOT_IN}).
 */
public record Condition(String field, ConditionOp op, Object value) {
}