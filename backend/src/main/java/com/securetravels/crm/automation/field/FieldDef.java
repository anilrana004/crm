package com.securetravels.crm.automation.field;

import java.util.Set;

/**
 * A registered, allow-listed field on a workflow entity.
 *
 * @param name          dotted field name used in conditions and actions.
 * @param type          value type.
 * @param writable      true if an {@code UPDATE_FIELD} action may assign it.
 * @param source        where the value comes from (for the UI and for audits).
 * @param allowedValues for ENUM fields: the closed set of legal values (also
 *                      the allow-list the validator checks expression
 *                      injections against — a value that is not in here is
 *                      rejected).
 */
public record FieldDef(String name, FieldType type, boolean writable, String source,
                       Set<String> allowedValues) {

    public static FieldDef text(String name, String source) {
        return new FieldDef(name, FieldType.TEXT, false, source, Set.of());
    }

    public static FieldDef textWritable(String name, String source) {
        return new FieldDef(name, FieldType.TEXT, true, source, Set.of());
    }

    public static FieldDef number(String name, String source) {
        return new FieldDef(name, FieldType.NUMBER, false, source, Set.of());
    }

    public static FieldDef booleanField(String name, String source) {
        return new FieldDef(name, FieldType.BOOLEAN, false, source, Set.of());
    }

    public static FieldDef date(String name, String source) {
        return new FieldDef(name, FieldType.DATE, false, source, Set.of());
    }

    public static FieldDef dateWritable(String name, String source) {
        return new FieldDef(name, FieldType.DATE, true, source, Set.of());
    }

    public static FieldDef dateTime(String name, String source) {
        return new FieldDef(name, FieldType.DATE_TIME, false, source, Set.of());
    }

    public static FieldDef uuid(String name, String source) {
        return new FieldDef(name, FieldType.UUID, false, source, Set.of());
    }

    public static FieldDef uuidWritable(String name, String source) {
        return new FieldDef(name, FieldType.UUID, true, source, Set.of());
    }

    public static FieldDef enums(String name, String source, String... values) {
        return new FieldDef(name, FieldType.ENUM, false, source, Set.of(values));
    }

    public static FieldDef enumsWritable(String name, String source, String... values) {
        return new FieldDef(name, FieldType.ENUM, true, source, Set.of(values));
    }
}