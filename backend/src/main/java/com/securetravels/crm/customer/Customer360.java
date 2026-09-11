package com.securetravels.crm.customer;

import com.securetravels.crm.common.audit.Auditable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * The canonical person record (DPDPA single source of truth). Automatically
 * linked from every Lead/Booking/Payment involving the same normalized
 * mobile number. Module 8 extends this with the full trip-history timeline.
 */
@Entity
@Table(name = "customer360", uniqueConstraints = {
        @UniqueConstraint(name = "uq_customer360_mobile_digits", columnNames = "mobile_digits"),
        @UniqueConstraint(name = "uq_customer360_email", columnNames = "email")
})
public class Customer360 extends Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "full_name", nullable = false, length = 200)
    private String fullName;

    @Column(name = "mobile_number", nullable = false, length = 30)
    private String mobileNumber;

    @Column(name = "mobile_digits", nullable = false, length = 20)
    private String mobileDigits;

    @Column(name = "whatsapp_number", length = 30)
    private String whatsappNumber;

    @Column(length = 255)
    private String email;

    @Column(name = "consent_given", nullable = false)
    private boolean consentGiven;

    @Column(name = "consent_captured_at")
    private Instant consentCapturedAt;

    @Column(name = "consent_scope", length = 200)
    private String consentScope;

    @Column(name = "marketing_opt_in", nullable = false)
    private boolean marketingOptIn;

    @Column(name = "total_trips", nullable = false)
    private int totalTrips;

    @Column(name = "last_trip_date")
    private LocalDate lastTripDate;

    @Column(name = "total_spent", nullable = false, precision = 12, scale = 2)
    private BigDecimal totalSpent = BigDecimal.ZERO;

    @Column(name = "suggest_offer", length = 200)
    private String suggestOffer;

    @Column(name = "offer_tags", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] offerTags;

    @Column(columnDefinition = "text")
    private String notes;

    public Customer360() {}

    public static Customer360 fromLead(String fullName, String mobileNumber, String mobileDigits,
                                       String whatsappNumber, String email, boolean consentGiven,
                                       String consentScope) {
        Customer360 c = new Customer360();
        c.fullName = fullName;
        c.mobileNumber = mobileNumber;
        c.mobileDigits = mobileDigits;
        c.whatsappNumber = whatsappNumber;
        c.email = email;
        c.consentGiven = consentGiven;
        c.consentCapturedAt = consentGiven ? Instant.now() : null;
        c.consentScope = consentScope;
        return c;
    }

    public UUID getId() { return id; }
    public String getFullName() { return fullName; }
    public String getMobileNumber() { return mobileNumber; }
    public String getMobileDigits() { return mobileDigits; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getEmail() { return email; }
    public boolean isConsentGiven() { return consentGiven; }
    public Instant getConsentCapturedAt() { return consentCapturedAt; }
    public String getConsentScope() { return consentScope; }
    public boolean isMarketingOptIn() { return marketingOptIn; }
    public int getTotalTrips() { return totalTrips; }
    public LocalDate getLastTripDate() { return lastTripDate; }
    public BigDecimal getTotalSpent() { return totalSpent; }
    public String getSuggestOffer() { return suggestOffer; }
    public String[] getOfferTags() { return offerTags; }
    public String getNotes() { return notes; }

    public void setFullName(String fullName) { this.fullName = fullName; }
    public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
    public void setEmail(String email) { this.email = email; }
    public void setConsentGiven(boolean consentGiven) { this.consentGiven = consentGiven; }
    public void setNotes(String notes) { this.notes = notes; }
    public void setMarketingOptIn(boolean marketingOptIn) { this.marketingOptIn = marketingOptIn; }
    public void setTotalTrips(int totalTrips) { this.totalTrips = totalTrips; }
    public void setLastTripDate(LocalDate lastTripDate) { this.lastTripDate = lastTripDate; }
    public void setTotalSpent(BigDecimal totalSpent) { this.totalSpent = totalSpent; }
    public void setSuggestOffer(String suggestOffer) { this.suggestOffer = suggestOffer; }
    public void setOfferTags(String[] offerTags) { this.offerTags = offerTags; }

    /**
     * Convenience copy so the returned array is safe to the caller (avoids
     * exposing the entity's internal array reference).
     */
    public String[] offerTagsCopy() {
        return offerTags == null ? new String[0] : Arrays.copyOf(offerTags, offerTags.length);
    }
}