package com.securetravels.crm.dashboard;

import com.securetravels.crm.common.audit.CreatedUpdated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Monthly sales target (legacy M6). user_id IS NULL means the company-wide
 * target for the month; the rest belong to an individual sales executive.
 */
@Entity
@Table(name = "sales_targets")
public class SalesTarget extends CreatedUpdated {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private LocalDate month;

    @Column(name = "target_bookings", nullable = false)
    private int targetBookings;

    @Column(name = "target_revenue", nullable = false, precision = 12, scale = 2)
    private BigDecimal targetRevenue = BigDecimal.ZERO;

    @Column(name = "created_by")
    private UUID createdBy;

    protected SalesTarget() {}

    public SalesTarget(UUID userId, LocalDate month, int targetBookings, BigDecimal targetRevenue, UUID createdBy) {
        this.userId = userId;
        this.month = month;
        this.targetBookings = targetBookings;
        this.targetRevenue = targetRevenue;
        this.createdBy = createdBy;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public LocalDate getMonth() { return month; }
    public int getTargetBookings() { return targetBookings; }
    public BigDecimal getTargetRevenue() { return targetRevenue; }
    public UUID getCreatedBy() { return createdBy; }

    public void setTargetBookings(int targetBookings) { this.targetBookings = targetBookings; }
    public void setTargetRevenue(BigDecimal targetRevenue) { this.targetRevenue = targetRevenue; }
}