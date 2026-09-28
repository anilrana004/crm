package com.securetravels.crm.booking;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Published when a booking reaches CONFIRMED (Module 4).
 *
 * <p>Carries resolved values rather than ids so the subscriber does not have to
 * re-read the graph after commit. Published <em>inside</em> the booking
 * transaction and consumed with
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, which is what
 * keeps a WhatsApp "you're booked" message from going out for a booking that
 * then failed to commit — and keeps a WhatsApp outage from rolling back a
 * booking the customer has already paid a deposit for.
 *
 * <p>{@code whatsappNumber} is already resolved to the best available channel
 * (WhatsApp number if the customer has one, else their mobile) so the subscriber
 * needs no knowledge of which column to prefer.
 */
public record BookingConfirmedEvent(
        UUID bookingId,
        UUID customerId,
        String bookingRef,
        String customerName,
        String mobile,
        String packageName,
        LocalDate travelDate) {
}
