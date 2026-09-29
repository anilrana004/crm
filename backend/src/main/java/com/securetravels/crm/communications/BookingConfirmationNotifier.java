package com.securetravels.crm.communications;

import com.securetravels.crm.booking.BookingConfirmedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Sends the customer their booking confirmation (Module 4).
 *
 * <p>Runs {@link TransactionPhase#AFTER_COMMIT}, which is the whole reason it
 * is not a direct call from {@code BookingService}:
 *
 * <ul>
 *   <li>A rollback cannot leave a "you are booked" message queued for a booking
 *       that does not exist — the most damaging possible failure for a
 *       customer-facing channel.</li>
 *   <li>A WhatsApp outage cannot roll back a booking confirmation the customer
 *       has already paid a deposit against.</li>
 * </ul>
 *
 * <p>Failures are logged, not rethrown: this runs after the business
 * transaction is already committed, so there is nothing left to protect, and an
 * unhandled exception here would only surface as a confusing error on an
 * unrelated request thread.
 */
@Component
public class BookingConfirmationNotifier {

    private static final Logger log = LoggerFactory.getLogger(BookingConfirmationNotifier.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final SendGateService sendGate;

    public BookingConfirmationNotifier(SendGateService sendGate) {
        this.sendGate = sendGate;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingConfirmed(BookingConfirmedEvent event) {
        try {
            SendDecision decision = sendGate.request(SendRequest.whatsappTemplate(
                    SubjectType.BOOKING,
                    event.bookingId(),
                    "BOOKING_CONFIRMED",
                    event.mobile(),
                    // Positional {{1}}..{{4}} for securetravels_booking_confirmed.
                    List.of(
                            blank(event.customerName()),
                            blank(event.bookingRef()),
                            blank(event.packageName()),
                            event.travelDate() == null ? "" : DATE.format(event.travelDate())),
                    null));
            if (!decision.accepted()) {
                // BOOKING_CONFIRMED is TRANSACTIONAL, so this can only happen on
                // an input error or a disabled template; report it loudly.
                log.warn("[whatsapp] booking {} confirmed but BOOKING_CONFIRMED not queued: {}",
                        event.bookingRef(), decision.reason());
            }
        } catch (RuntimeException e) {
            // The booking is confirmed either way. Log loudly — this is a
            // customer who will not hear from us.
            log.error("[whatsapp] could not queue BOOKING_CONFIRMED for booking {} ({})",
                    event.bookingId(), event.bookingRef(), e);
        }
    }

    /** A blank placeholder still occupies its position, or every later value shifts. */
    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
