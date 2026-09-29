package com.securetravels.crm.automation.definition;

import java.util.List;

/**
 * A node of the AND/OR/NOT condition tree.
 *
 * <p>Leaves live in {@code conditions} (field comparisons); sub-trees live in
 * {@code children}. Shape rules (enforced by the validator):
 * <ul>
 *   <li>{@code NOT} — exactly one child, zero conditions.</li>
 *   <li>{@code AND}/{@code OR} — at least one condition or child.</li>
 * </ul>
 */
public record ConditionNode(
        ConditionOperator operator,
        List<Condition> conditions,
        List<ConditionNode> children) {

    /** Convenience: a single leaf condition wrapped in an implicit AND. */
    public static ConditionNode leaf(Condition leaf) {
        return new ConditionNode(ConditionOperator.AND, List.of(leaf), List.of());
    }
}