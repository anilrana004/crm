package com.securetravels.crm.automation.field;

/**
 * Type of a registered field. Drives operator/value compatibility and the
 * UPDATE_FIELD write checks; {@link FieldDef#source} carries the human
 * explanation ("where the value comes from"), so a wrong-looking field on a
 * form has a trace.
 */
public enum FieldType {
    TEXT, NUMBER, BOOLEAN, DATE, DATE_TIME, UUID, ENUM
}