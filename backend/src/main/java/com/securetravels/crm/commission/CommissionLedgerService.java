package com.securetravels.crm.commission;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.lead.Lead;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Writes the revenue-attribution snapshot at the moment of crediting.
 *
 * <p>Called from {@code BookingService} inside the booking status transaction,
 * so a credit and the status change that justifies it commit or roll back
 * together. There is no window in which a booking is confirmed but unattributed.
 */
@Service
public class CommissionLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CommissionLedgerService.class);

    private final CommissionLedgerRepository ledger;

    public CommissionLedgerService(CommissionLedgerRepository ledger) {
        this.ledger = ledger;
    }

    /**
     * Credit {@code booking} to the owning consultant of {@code lead}.
     *
     * <p>Idempotent on booking_id, because both a retried request and a
     * replayed webhook can reach this path and a double credit is a payroll bug
     * that is expensive to notice and embarrassing to explain.
     *
     * @return the credit, or empty when there is no consultant to attribute to
     */
    @Transactional
    public Optional<CommissionLedger> credit(Booking booking, Lead lead, Instant at) {
        if (lead == null) {
            log.warn("Booking {} confirmed with no lead; no consultant to attribute revenue to",
                    booking.getId());
            return Optional.empty();
        }
        if (lead.getOwnerId() == null) {
            log.warn("Booking {} confirmed via lead {} which has no owner; no consultant to attribute revenue to",
                    booking.getId(), lead.getId());
            return Optional.empty();
        }
        if (ledger.existsByBookingId(booking.getId())) {
            log.debug("Booking {} already has a commission credit; ignoring duplicate", booking.getId());
            return ledger.findByBookingId(booking.getId());
        }

        CommissionLedger row = CommissionLedger.credit(booking, lead.getId(), lead.getOwnerId(), at);
        CommissionLedger saved = ledger.save(row);
        log.info("Commission credit for booking {} -> consultant {} (net {})",
                booking.getId(), lead.getOwnerId(), saved.getNetAmount());
        return Optional.of(saved);
    }

    /**
     * Revoke the credit for a cancelled booking.
     *
     * <p>Returns true only if this call performed the revocation, so a caller can
     * tell "I withdrew it" from "it was already withdrawn" without re-reading the
     * row.
     */
    @Transactional
    public boolean revoke(UUID bookingId, String reason) {
        return ledger.findByBookingId(bookingId)
                .map(row -> {
                    boolean changed = row.revoke(Instant.now(), reason);
                    if (changed) {
                        log.info("Commission credit revoked for booking {}: {}", bookingId, reason);
                    }
                    return changed;
                })
                .orElseGet(() -> {
                    // Not an error: a booking can be cancelled without ever having
                    // reached a commissionable status, in which case nothing was
                    // credited and nothing needs withdrawing.
                    log.debug("No commission credit to revoke for booking {}", bookingId);
                    return false;
                });
    }
}
