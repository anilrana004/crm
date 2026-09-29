package com.securetravels.crm.automation.definition;

/**
 * The trigger declaration, validated by kind in {@code WorkflowValidator}.
 *
 * <p>Only one of {@code event} / {@code cron}+{@code entity} /
 * {@code dateField}+{@code entity}{@code +offsetDays} is meaningful, selected
 * by {@link TriggerKind}. Unused fields must be absent or null.
 *
 * @param event      EVENT only: dotted event name of the form
 *                   {@code {entity}.{action}} (e.g. {@code lead.updated}).
 * @param entity     CRON / DATE_OFFSET only: the subject entity (e.g.
 *                   {@code lead}), which selects the field registry.
 * @param cron       CRON only: six-field cron expression.
 * @param dateField  DATE_OFFSET only: a date field on the entity.
 * @param offsetDays DATE_OFFSET only: days to subtract from the field's date
 *                   before comparing to today ({@code 0} = fire on the date,
 *                   positive = N days before).
 */
public record TriggerDefinition(
        TriggerKind kind,
        String event,
        String entity,
        String cron,
        String dateField,
        Integer offsetDays) {

    public static TriggerDefinition event(String event) {
        return new TriggerDefinition(TriggerKind.EVENT, event, null, null, null, null);
    }
}