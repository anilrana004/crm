package com.securetravels.crm.automation.runtime;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * A frozen read of the subject row behind a run (Phase 6 Module 2).
 *
 * <p>Built once per step execution from the {@code FieldRegistry} sources
 * (never raw SQL in the executor), values pre-normalised by field type so
 * conditions and actions compare clean values, not driver types. The snapshot
 * is deliberately a plain copy: the engine reads, never writes through it.
 */
public final class RuntimeSnapshot {

    private final String entity;
    private final UUID subjectId;
    private final Map<String, Object> values;

    public RuntimeSnapshot(String entity, UUID subjectId, Map<String, Object> values) {
        this.entity = entity;
        this.subjectId = subjectId;
        this.values = values;
    }

    public String entity() { return entity; }
    public UUID subjectId() { return subjectId; }

    public Object value(String field) {
        return field == null ? null : values.get(field);
    }

    public String asString(String field) {
        Object v = value(field);
        return v == null ? null : String.valueOf(v);
    }

    public UUID asUuid(String field) {
        Object v = value(field);
        return v == null ? null : v instanceof UUID u ? u : UUID.fromString(String.valueOf(v));
    }

    public BigDecimal asNumber(String field) {
        Object v = value(field);
        return v == null ? null
                : v instanceof BigDecimal b ? b
                : v instanceof Number n ? BigDecimal.valueOf(n.doubleValue())
                : new BigDecimal(String.valueOf(v));
    }

    public LocalDate asDate(String field) {
        Object v = value(field);
        return v == null ? null
                : v instanceof LocalDate d ? d
                : v instanceof java.sql.Date d ? d.toLocalDate()
                : LocalDate.parse(String.valueOf(v));
    }

    public Instant asInstant(String field) {
        Object v = value(field);
        return v == null ? null
                : v instanceof Instant i ? i
                : v instanceof java.time.OffsetDateTime o ? o.toInstant()
                : v instanceof java.time.LocalDateTime l ? l.toInstant(java.time.ZoneOffset.UTC)
                : Instant.parse(String.valueOf(v));
    }

    public boolean asBoolean(String field) {
        Object v = value(field);
        return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
    }
}