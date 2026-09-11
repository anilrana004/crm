package com.securetravels.crm.operations;

import com.securetravels.crm.common.audit.Auditable;
import com.securetravels.crm.payment.Payment;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Auto-generated once on booking confirmation (invariant I6, enforced by the
 * unique booking_id). For FIXED_BATCH the guide/transport derive from the
 * batch; for CUSTOM_FIT hotel/vehicle/driver are per-handoff.
 */
@Entity
@Table(name = "operations_handoffs",
        uniqueConstraints = @UniqueConstraint(name = "uq_ops_handoff_booking", columnNames = "booking_id"),
        indexes = {
                @Index(name = "idx_ops_handoffs_travel_date", columnList = "travel_date")
        })
public class OperationsHandoff extends Auditable {

    public enum HandoffStatus { NOT_ARRANGED, PENDING, CONFIRMED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "ops_ref", nullable = false, unique = true, length = 20)
    private String opsRef;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "travel_date", nullable = false)
    private LocalDate travelDate;

    @Column(nullable = false)
    private int pax;

    @Enumerated(EnumType.STRING)
    @Column(name = "hotel_status", nullable = false, length = 20)
    private HandoffStatus hotelStatus = HandoffStatus.NOT_ARRANGED;

    @Enumerated(EnumType.STRING)
    @Column(name = "transport_status", nullable = false, length = 20)
    private HandoffStatus transportStatus = HandoffStatus.NOT_ARRANGED;

    @Column(name = "guide_id")
    private UUID guideId;

    @Column(name = "driver_id")
    private UUID driverId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", length = 20)
    private Payment.Status paymentStatus;

    @Column(name = "trip_sheet_generated_at")
    private Instant tripSheetGeneratedAt;

    @Column(columnDefinition = "text")
    private String notes;

    protected OperationsHandoff() {}

    public OperationsHandoff(UUID bookingId, String opsRef, LocalDate travelDate, int pax,
                             UUID batchId, UUID guideId) {
        this.bookingId = bookingId;
        this.opsRef = opsRef;
        this.travelDate = travelDate;
        this.pax = pax;
        this.batchId = batchId;
        this.guideId = guideId;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public String getOpsRef() { return opsRef; }
    public UUID getBatchId() { return batchId; }
    public LocalDate getTravelDate() { return travelDate; }
    public int getPax() { return pax; }
    public HandoffStatus getHotelStatus() { return hotelStatus; }
    public HandoffStatus getTransportStatus() { return transportStatus; }
    public UUID getGuideId() { return guideId; }
    public UUID getDriverId() { return driverId; }
    public Payment.Status getPaymentStatus() { return paymentStatus; }
    public Instant getTripSheetGeneratedAt() { return tripSheetGeneratedAt; }
    public String getNotes() { return notes; }

    public void setHotelStatus(HandoffStatus hotelStatus) { this.hotelStatus = hotelStatus; }
    public void setTransportStatus(HandoffStatus transportStatus) { this.transportStatus = transportStatus; }
    public void setGuideId(UUID guideId) { this.guideId = guideId; }
    public void setDriverId(UUID driverId) { this.driverId = driverId; }
    public void setPaymentStatus(Payment.Status paymentStatus) { this.paymentStatus = paymentStatus; }
    public void setTripSheetGeneratedAt(Instant tripSheetGeneratedAt) { this.tripSheetGeneratedAt = tripSheetGeneratedAt; }
    public void setNotes(String notes) { this.notes = notes; }
}