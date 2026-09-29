package com.securetravels.crm.automation.definition;

/**
 * Leaf comparison operators. Operator/type compatibility is enforced by the
 * validator against the field registry (a NUMBER field may not use
 * {@code CONTAINS}, a TEXT field may not use {@code GT}, and so on).
 *
 * <p>{@code IS_NULL} / {@code NOT_NULL} take no value; everything else does.
 */
public enum ConditionOp {
    EQ, NEQ, GT, GTE, LT, LTE, IN, NOT_IN, CONTAINS, STARTS_WITH, IS_NULL, NOT_NULL
}