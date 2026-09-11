package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.CreatedOnly;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Minimal Phase-1 guide record (name/phone/day rate). Vendor management,
 * availability calendars and the double-booking conflict window land later;
 * the conflict check itself (item 7) reads this table.
 */
@Entity
@Table(name = "guides")
public class Guide extends CreatedOnly {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(name = "daily_rate", precision = 10, scale = 2)
    private BigDecimal dailyRate;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected Guide() {}

    public Guide(String fullName, String phone, BigDecimal dailyRate) {
        this.fullName = fullName;
        this.phone = phone;
        this.dailyRate = dailyRate;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getPhone() { return phone; }
    public BigDecimal getDailyRate() { return dailyRate; }
    public boolean isActive() { return active; }

    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setDailyRate(BigDecimal dailyRate) { this.dailyRate = dailyRate; }
    public void setActive(boolean active) { this.active = active; }
}