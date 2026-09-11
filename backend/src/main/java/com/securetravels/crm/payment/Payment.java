package com.securetravels.crm.payment;

import com.securetravels.crm.common.audit.Auditable;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Manual payment tracking in Phase 1. Only the gateway's transaction
 * reference is ever stored — never card data (security requirement 10).
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "idx_payments_booking", columnList = "booking_id")
})
public class Payment extends Auditable {

    public enum AmountType { ADVANCE, BALANCE, FULL }
    public enum Status { PENDING, PARTIAL, COMPLETED, OVERDUE, CANCELLED, REFUNDED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_type", nullable = false, length = 20)
    private AmountType amountType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "gateway_ref", length = 120)
    private String gatewayRef;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "recorded_by")
    private UUID recordedBy;

    protected Payment() {}

    public Payment(UUID bookingId, BigDecimal amount, AmountType amountType, LocalDate dueDate) {
        this.bookingId = bookingId;
        this.amount = amount;
        this.amountType = amountType;
        this.dueDate = dueDate;
    }

    public UUID getId() { return id; }
    public UUID getBookingId() { return bookingId; }
    public BigDecimal getAmount() { return amount; }
    public AmountType getAmountType() { return amountType; }
    public Status getStatus() { return status; }
    public LocalDate getDueDate() { return dueDate; }
    public Instant getPaidAt() { return paidAt; }
    public String getGatewayRef() { return gatewayRef; }
    public String getNotes() { return notes; }
    public UUID getRecordedBy() { return recordedBy; }

    public void setStatus(Status status) { this.status = status; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public void setGatewayRef(String gatewayRef) { this.gatewayRef = gatewayRef; }
    public void setRecordedBy(UUID recordedBy) { this.recordedBy = recordedBy; }
    public void setNotes(String notes) { this.notes = notes; }
}