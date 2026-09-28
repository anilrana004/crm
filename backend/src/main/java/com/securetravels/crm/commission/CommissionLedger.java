package com.securetravels.crm.commission;

import com.securetravels.crm.booking.Booking;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One credit of commissionable revenue to one consultant for one booking.
 *
 * <p>Deliberately NOT extending {@code Auditable}: this table has no
 * created_at/updated_at/version and must never gain them. The row is an
 * immutable event record, not a mutable aggregate. Its single timestamp is
 * {@code creditedAt}, and corrections are expressed by revoking, never by
 * editing.
 *
 * <p>Nothing on this row may be re-derived from the lead. See
 * {@code V13__reporting_commission_ledger_and_fts.sql} for why a live join to
 * {@code leads.owner_id} silently rewrites last quarter's payroll.
 *
 * <p>Owner and Phase 7 will widen this into a full commission statement
 * (rate/percentage per consultant, payable period, payout run). The identity
 * and the snapshot semantics are already what they will need, so that work is
 * additive rather than a rewrite.
 */
@Entity
@Table(name = "sales_commission_ledger", uniqueConstraints = {
        // The idempotency guard: a retried status-change call must not credit twice.
        @UniqueConstraint(name = "uq_commission_ledger_booking", columnNames = "booking_id")
})
public class CommissionLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false, unique = true)
    private UUID bookingId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "consultant_id", nullable = false)
    private UUID consultantId;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "credited_at", nullable = false)
    private Instant creditedAt;

    /**
     * The booking's status at the moment of credit. A later COMPLETED
     * transition must not overwrite a CONFIRMED credit, which is why this is a
     * snapshot rather than a live relation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "booking_status_at_credit", nullable = false, length = 32)
    private Booking.Status bookingStatusAtCredit;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    @Column(name = "discount_amount", nullable = false)
    private BigDecimal discountAmount;

    @Column(name = "tax_amount", nullable = false)
    private BigDecimal taxAmount;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", columnDefinition = "text")
    private String revokeReason;

    protected CommissionLedger() {
    }

    public static CommissionLedger credit(Booking booking,
                                          UUID leadId,
                                          UUID consultantId,
                                          Instant creditedAt) {
        CommissionLedger row = new CommissionLedger();
        row.bookingId = booking.getId();
        row.leadId = leadId;
        row.consultantId = consultantId;
        row.tripId = booking.getTripId();
        row.batchId = booking.getBatchId();
        row.creditedAt = creditedAt;
        row.bookingStatusAtCredit = booking.getStatus();
        row.grossAmount = booking.getTotalAmount();
        row.discountAmount = booking.getDiscountAmount();
        row.taxAmount = booking.getTaxAmount();
        row.netAmount = netOf(booking);
        return row;
    }

    /**
     * Net booked value. Duplicated here as well as in SQL because the backfill
     * needs it without the JVM. The two definitions are asserted equal by
     * {@code CommissionLedgerServiceTest} so they cannot drift apart.
     */
    static BigDecimal netOf(Booking booking) {
        BigDecimal discount = nz(booking.getDiscountAmount());
        BigDecimal tax = nz(booking.getTaxAmount());
        return booking.getTotalAmount().subtract(discount).add(tax);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Explicit reversal. A commission dispute is settled by showing when the
     * credit appeared and when it was withdrawn, so the row survives.
     *
     * <p>Idempotent: a second revoke on an already-revoked row is a no-op rather
     * than an error, because cancellation can legitimately be attempted twice
     * (user retry, webhook replay).
     */
    public boolean revoke(Instant at, String reason) {
        if (revokedAt != null) return false;
        this.revokedAt = at;
        this.revokeReason = reason;
        return true;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    /** Net value still attributable, or zero once revoked. */
    public BigDecimal effectiveNetAmount() {
        return revokedAt == null ? netAmount : BigDecimal.ZERO;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookingId() {
        return bookingId;
    }

    public UUID getLeadId() {
        return leadId;
    }

    public UUID getConsultantId() {
        return consultantId;
    }

    public UUID getTripId() {
        return tripId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public Instant getCreditedAt() {
        return creditedAt;
    }

    public Booking.Status getBookingStatusAtCredit() {
        return bookingStatusAtCredit;
    }

    public BigDecimal getGrossAmount() {
        return grossAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public BigDecimal getTaxAmount() {
        return taxAmount;
    }

    public BigDecimal getNetAmount() {
        return netAmount;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokeReason() {
        return revokeReason;
    }
}
