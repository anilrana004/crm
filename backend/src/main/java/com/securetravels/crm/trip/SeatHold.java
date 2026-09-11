package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.CreatedOnly;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Provisional hold on a batch's seat pool (2 hours, auto-released by the
 * scheduled job). HELD -> CONFIRMED backfills booking_id on booking
 * confirmation; booking confirmation for FIXED_BATCH requires a valid
 * CONFIRMED hold (invariant I5).
 */
@Entity
@Table(name = "seat_holds", indexes = {
        @Index(name = "idx_seat_holds_batch_status", columnList = "batch_id, status")
})
public class SeatHold extends CreatedOnly {

    public enum Status { HELD, CONFIRMED, RELEASED, EXPIRED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "num_seats", nullable = false)
    private int numSeats;

    @Column(name = "held_until", nullable = false)
    private Instant heldUntil;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.HELD;

    protected SeatHold() {}

    public SeatHold(UUID batchId, int numSeats, Instant heldUntil) {
        this.batchId = batchId;
        this.numSeats = numSeats;
        this.heldUntil = heldUntil;
    }

    public UUID getId() { return id; }
    public UUID getBatchId() { return batchId; }
    public UUID getBookingId() { return bookingId; }
    public int getNumSeats() { return numSeats; }
    public Instant getHeldUntil() { return heldUntil; }
    public Status getStatus() { return status; }

    public void confirm(UUID bookingId) {
        this.bookingId = bookingId;
        this.status = Status.CONFIRMED;
    }

    /** Link the hold to its booking at creation (status stays HELD, seats counted). */
    public void assignToBooking(UUID bookingId) {
        this.bookingId = bookingId;
    }

    public void mark(Status status) { this.status = status; }
}