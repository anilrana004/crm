package com.securetravels.crm.automation.definition;

/**
 * Where a workflow's activation comes from.
 *
 * <ul>
 *   <li>{@link #EVENT} — a domain event {@code {entity}.{action}} (e.g.
 *       {@code lead.updated}, {@code booking.confirmed}) emitted by
 *       {@code automation.trigger.EventPublisher}.</li>
 *   <li>{@link #CRON} — a periodically evaluated scan over the trigger's
 *       entity (six-field cron expression).</li>
 *   <li>{@link #DATE_OFFSET} — a row becomes eligible when a date field on the
 *       trigger's entity crosses today minus {@code offsetDays} (e.g.
 *       {@code date_field − Nd}, the nurture-sequence shape).</li>
 * </ul>
 */
public enum TriggerKind {
    EVENT, CRON, DATE_OFFSET
}