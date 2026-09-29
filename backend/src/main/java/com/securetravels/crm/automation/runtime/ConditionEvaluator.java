package com.securetravels.crm.automation.runtime;

import com.securetravels.crm.automation.definition.Condition;
import com.securetravels.crm.automation.definition.ConditionNode;
import com.securetravels.crm.automation.definition.ConditionOp;
import com.securetravels.crm.automation.definition.ConditionOperator;
import com.securetravels.crm.automation.field.FieldDef;
import com.securetravels.crm.automation.field.FieldRegistry;
import com.securetravels.crm.automation.field.FieldType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates the conditions over a {@link RuntimeSnapshot} (Phase 6 Module 2).
 *
 * <p>Comparisons are typed by the field registry so values loaded from the
 * database (numbers, dates, uuids) compare correctly against JSON literals,
 * and null/missing values follow SQL-ish null semantics (a comparison against
 * null is false, {@code IS_NULL} is how you ask). The validator has already
 * pinned operator/type compatibility, so this evaluator trusts its inputs.
 */
@Component
public class ConditionEvaluator {

    public boolean matches(ConditionNode node, RuntimeSnapshot snapshot) {
        if (node == null) {
            return true;
        }
        if (node.operator() == ConditionOperator.AND) {
            return allConditions(node.conditions(), snapshot) && allNodes(node.children(), snapshot);
        }
        if (node.operator() == ConditionOperator.OR) {
            return anyConditions(node.conditions(), snapshot) || anyNodes(node.children(), snapshot);
        }
        // NOT: exactly one child (validator-enforced).
        return !matches(node.children().get(0), snapshot);
    }

    private boolean allConditions(List<Condition> conditions, RuntimeSnapshot snapshot) {
        for (Condition c : conditions) {
            if (!matches(c, snapshot)) return false;
        }
        return true;
    }

    private boolean anyConditions(List<Condition> conditions, RuntimeSnapshot snapshot) {
        for (Condition c : conditions) {
            if (matches(c, snapshot)) return true;
        }
        return false;
    }

    private boolean allNodes(List<ConditionNode> children, RuntimeSnapshot snapshot) {
        for (ConditionNode n : children) {
            if (!matches(n, snapshot)) return false;
        }
        return true;
    }

    private boolean anyNodes(List<ConditionNode> children, RuntimeSnapshot snapshot) {
        for (ConditionNode n : children) {
            if (matches(n, snapshot)) return true;
        }
        return false;
    }

    public boolean matches(Condition condition, RuntimeSnapshot snapshot) {
        Object actual = snapshot.value(condition.field());
        FieldDef def = FieldRegistry.lookup(snapshot.entity(), condition.field()).orElse(null);
        FieldType type = def == null ? FieldType.TEXT : def.type();

        ConditionOp op = condition.op();
        if (op == ConditionOp.IS_NULL) return actual == null;
        if (op == ConditionOp.NOT_NULL) return actual != null;
        if (actual == null) return false;

        return switch (op) {
            case EQ -> compare(type, actual, condition.value()) == 0;
            case NEQ -> compare(type, actual, condition.value()) != 0;
            case GT -> compare(type, actual, condition.value()) > 0;
            case GTE -> compare(type, actual, condition.value()) >= 0;
            case LT -> compare(type, actual, condition.value()) < 0;
            case LTE -> compare(type, actual, condition.value()) <= 0;
            case IN -> in(type, actual, condition.value());
            case NOT_IN -> !in(type, actual, condition.value());
            case CONTAINS -> asText(actual).contains(String.valueOf(condition.value()));
            case STARTS_WITH -> asText(actual).startsWith(String.valueOf(condition.value()));
            case IS_NULL, NOT_NULL -> throw new IllegalStateException("handled above");
        };
    }

    private boolean in(FieldType type, Object actual, Object expected) {
        if (expected instanceof List<?> list) {
            for (Object item : list) {
                if (compare(type, actual, item) == 0) return true;
            }
        }
        return compare(type, actual, expected) == 0;
    }

    private int compare(FieldType type, Object actual, Object expected) {
        return switch (type) {
            case TEXT, ENUM -> asText(actual).compareTo(String.valueOf(expected));
            case NUMBER -> asDecimal(actual).compareTo(asDecimal(expected));
            case BOOLEAN -> Boolean.compare(asBool(actual), asBool(expected));
            case DATE -> asDate(actual).compareTo(asDate(expected));
            case DATE_TIME -> asInstant(actual).compareTo(asInstant(expected));
            case UUID -> asUuid(actual).compareTo(asUuid(expected));
        };
    }

    private String asText(Object v) {
        return v instanceof String s ? s : String.valueOf(v);
    }

    private BigDecimal asDecimal(Object v) {
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(((Number) n).doubleValue());
        return new BigDecimal(String.valueOf(v));
    }

    private boolean asBool(Object v) {
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private LocalDate asDate(Object v) {
        if (v instanceof LocalDate d) return d;
        if (v instanceof java.sql.Date d) return d.toLocalDate();
        return LocalDate.parse(String.valueOf(v));
    }

    private Instant asInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp t) return t.toInstant();
        if (v instanceof java.time.OffsetDateTime o) return o.toInstant();
        return Instant.parse(String.valueOf(v));
    }

    private UUID asUuid(Object v) {
        if (v instanceof UUID u) return u;
        return UUID.fromString(String.valueOf(v));
    }
}