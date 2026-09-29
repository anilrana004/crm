package com.securetravels.crm.automation.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A single fact about a subject, raised by a domain service and observed by
 * the automation engine (Phase 6).
 *
 * <p>{@code entity} is one of the FieldRegistry entities ({@code lead},
 * {@code booking}, ...) and {@code action} is the second segment of the
 * trigger event name ({@code updated}, {@code confirmed}, ...) — so a publish
 * call {@code publisher.publish(new AutomationEvent("booking", "confirmed",
 * bookingId, Instant.now()))} matches workflows triggered on
 * {@code booking.confirmed}.
 *
 * <p>The record is deliberately bare: Module 2 adds the durability (outbox
 * idempotency key) and the run bookkeeping around it; the event itself stays
 * a value with no behaviour.
 *
 * @param entity     FieldRegistry entity name, first trigger segment.
 * @param action     business verb, second trigger segment.
 * @param subjectId  the row that changed.
 * @param occurredAt when it happened (used for WAIT-base timing and audits).
 */
public record AutomationEvent(String entity, String action, UUID subjectId, Instant occurredAt) {

    /**
     * The idempotency key for this event, preserved verbatim across the
     * outbox (Module 2): replaying the same subject+action at the same
     * instant must not start the same run twice.
     */
    public String eventKey() {
        return entity + ":" + action + ":" + subjectId + ":" + occurredAt;
    }
}