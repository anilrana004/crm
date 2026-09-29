package com.securetravels.crm.automation.definition;

/** Tree node operators for the JSON condition tree. */
public enum ConditionOperator {
    /** Every child must hold. */
    AND,
    /** At least one child must hold. */
    OR,
    /** Exactly one child, negated. */
    NOT
}