package com.securetravels.crm.booking;

import com.securetravels.crm.common.audit.Auditable;
import com.securetravels.crm.trip.BookingType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One shared shape for both flows. I1/I2 (type must match trip, batch
 * presence rules) are enforced at the database by trg_booking_consistency
 * and re-checked in the booking service.
 *
 * FIXED_BATCH: batch_id required; seats confirmed via a SeatHold (I5).
 * CUSTOM_FIT: batch_id must be null; date is customer-chosen.
 */
@Entity
@Table(name = "bookings", indexes = {
        @Index(name = "idx_bookings_customer", columnList = "customer_id"),
        @Index(name = "idx_bookings_trip", columnList = "trip_id"),
        @Index(name = "idx_bookings_batch", columnList = "batch_id"),
        @Index(name = "idx_bookings_status", columnList = "status")
})
public class Booking extends Auditable {

    public enum Status { QUOTATION, CONFIRMED, COMPLETED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_ref", nullable = false, unique = true, length = 20)
    private String bookingRef;

    @Column(name = "trip_id", nullable = false)
    private UUID tripId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_type", nullable = false, length = 30)
    private BookingType bookingType;

    @Column(name = "num_travellers", nullable = false)
    private int numTravellers;

    @Column(name = "total_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 12, scale = 2)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUOTATION;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(name = "discount_approved_by")
    private UUID discountApprovedBy;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "lead_id")
    private UUID leadId;

    protected Booking() {}

    /** Net amount receivable after discount; used by payment tracking. */
    public BigDecimal netAmount() {
        return totalAmount.subtract(discountAmount);
    }

    public UUID getId() { return id; }
    public String getBookingRef() { return bookingRef; }
    public UUID getTripId() { return tripId; }
    public UUID getBatchId() { return batchId; }
    public UUID getCustomerId() { return customerId; }
    public BookingType getBookingType() { return bookingType; }
    public int getNumTravellers() { return numTravellers; }
    public BigDecimal getTotalAmount() { return totalAmount; }
    public BigDecimal getDiscountAmount() { return discountAmount; }
    public BigDecimal getTaxAmount() { return taxAmount; }
    public Status getStatus() { return status; }
    public LocalDate getTravelDate() { return travelDate; }
    public UUID getDiscountApprovedBy() { return discountApprovedBy; }
    public String getNotes() { return notes; }
    public UUID getCreatedBy() { return createdBy; }
    public UUID getLeadId() { return leadId; }

    public void setBookingRef(String bookingRef) { this.bookingRef = bookingRef; }
    public void setTripId(UUID tripId) { this.tripId = tripId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public void setCustomerId(UUID customerId) { this.customerId = customerId; }
    public void setBookingType(BookingType bookingType) { this.bookingType = bookingType; }
    public void setNumTravellers(int numTravellers) { this.numTravellers = numTravellers; }
    public void setTotalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    public void setDiscountAmount(BigDecimal discountAmount) { this.discountAmount = discountAmount; }
    public void setTaxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; }
    public void setStatus(Status status) { this.status = status; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public void setDiscountApprovedBy(UUID discountApprovedBy) { this.discountApprovedBy = discountApprovedBy; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
    public void setLeadId(UUID leadId) { this.leadId = leadId; }
}