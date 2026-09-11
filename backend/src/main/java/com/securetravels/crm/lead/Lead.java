package com.securetravels.crm.lead;

import com.securetravels.crm.common.audit.Auditable;
import com.securetravels.crm.common.util.XssSanitizer;
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

@Entity
@Table(name = "leads", indexes = {
        @Index(name = "idx_leads_mobile_digits", columnList = "mobile_digits"),
        @Index(name = "idx_leads_owner_status", columnList = "owner_id, status, created_at"),
        @Index(name = "idx_leads_email", columnList = "email"),
        @Index(name = "idx_leads_created_at", columnList = "created_at")
})
public class Lead extends Auditable {

    public enum Status { NEW, INTERESTED, QUOTATION_SENT, BOOKING_CONFIRMED, LOST }
    public enum Source {
        GOOGLE_ADS, FACEBOOK_ADS, INSTAGRAM, WEBSITE, WHATSAPP,
        REFERRAL, JUSTDIAL, WALK_IN, B2B, EXISTING_CUSTOMER, OTHER
    }
    public enum LostReason {
        PRICE_TOO_HIGH, DATES_UNAVAILABLE, CHOSE_COMPETITOR, WENT_SILENT, NOT_GENUINE, POSTPONED
    }
    public enum Heat { HOT, WARM, COLD }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_name", nullable = false, length = 200)
    private String customerName;

    @Column(name = "mobile_number", nullable = false, length = 30)
    private String mobileNumber;

    @Column(name = "mobile_digits", nullable = false, length = 20)
    private String mobileDigits;

    @Column(name = "whatsapp_number", length = 30)
    private String whatsappNumber;

    @Column(length = 255)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Source source;

    @Column(length = 120)
    private String destination;

    @Column(name = "trip_id")
    private UUID tripId;

    @Column(name = "travel_date")
    private LocalDate travelDate;

    @Column(name = "num_persons", nullable = false)
    private int numPersons = 1;

    @Column(precision = 12, scale = 2)
    private BigDecimal budget;

    @Column(name = "owner_id")
    private UUID ownerId;

    /** Canonical person record (set when the normalized phone matches a Customer360 on create). */
    @Column(name = "customer360_id")
    private UUID customer360Id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.NEW;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Heat heat = Heat.COLD;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(columnDefinition = "text")
    private String remarks;

    // DPDPA consent captured at creation (security requirement 12)
    @Column(name = "consent_given", nullable = false)
    private boolean consentGiven;

    @Column(name = "consent_captured_at")
    private Instant consentCapturedAt;

    @Column(name = "consent_scope", length = 200)
    private String consentScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason", length = 30)
    private LostReason lostReason;

    @Column(name = "duplicate_of_lead_id")
    private UUID duplicateOfLeadId;

    @Column(name = "last_contacted_at")
    private Instant lastContactedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    public Lead() {}

    public void markLost(LostReason reason) {
        this.status = Status.LOST;
        this.lostReason = reason;
        this.heat = Heat.COLD;
    }

    /** Remarks are OWASP-sanitized before persisting (stored-XSS guard). */
    public void setRemarks(String remarks) { this.remarks = XssSanitizer.text(remarks); }

    /** Customer names are OWASP-sanitized before persisting (stored-XSS guard). */
    public void setCustomerName(String customerName) { this.customerName = XssSanitizer.text(customerName); }

    // --- getters ---
    public UUID getId() { return id; }
    public String getCustomerName() { return customerName; }
    public String getMobileNumber() { return mobileNumber; }
    public String getMobileDigits() { return mobileDigits; }
    public String getWhatsappNumber() { return whatsappNumber; }
    public String getEmail() { return email; }
    public Source getSource() { return source; }
    public String getDestination() { return destination; }
    public UUID getTripId() { return tripId; }
    public LocalDate getTravelDate() { return travelDate; }
    public int getNumPersons() { return numPersons; }
    public BigDecimal getBudget() { return budget; }
    public UUID getOwnerId() { return ownerId; }
    public UUID getCustomer360Id() { return customer360Id; }
    public Status getStatus() { return status; }
    public Heat getHeat() { return heat; }
    public LocalDate getFollowUpDate() { return followUpDate; }
    public String getRemarks() { return remarks; }
    public boolean isConsentGiven() { return consentGiven; }
    public Instant getConsentCapturedAt() { return consentCapturedAt; }
    public String getConsentScope() { return consentScope; }
    public Lead.LostReason getLostReason() { return lostReason; }
    public UUID getDuplicateOfLeadId() { return duplicateOfLeadId; }
    public Instant getLastContactedAt() { return lastContactedAt; }
    public UUID getCreatedBy() { return createdBy; }

    public void setMobileNumber(String mobileNumber) { this.mobileNumber = mobileNumber; }
    public void setMobileDigits(String mobileDigits) { this.mobileDigits = mobileDigits; }
    public void setWhatsappNumber(String whatsappNumber) { this.whatsappNumber = whatsappNumber; }
    public void setEmail(String email) { this.email = email; }
    public void setSource(Source source) { this.source = source; }
    public void setDestination(String destination) { this.destination = destination; }
    public void setTripId(UUID tripId) { this.tripId = tripId; }
    public void setTravelDate(LocalDate travelDate) { this.travelDate = travelDate; }
    public void setNumPersons(int numPersons) { this.numPersons = numPersons; }
    public void setBudget(BigDecimal budget) { this.budget = budget; }
    public void setOwnerId(UUID ownerId) { this.ownerId = ownerId; }
    public void setCustomer360Id(UUID customer360Id) { this.customer360Id = customer360Id; }
    public void setStatus(Status status) { this.status = status; }
    public void setHeat(Heat heat) { this.heat = heat; }
    public void setFollowUpDate(LocalDate followUpDate) { this.followUpDate = followUpDate; }
    public void setConsentGiven(boolean consentGiven) { this.consentGiven = consentGiven; }
    public void setConsentCapturedAt(Instant consentCapturedAt) { this.consentCapturedAt = consentCapturedAt; }
    public void setConsentScope(String consentScope) { this.consentScope = consentScope; }
    public void setLostReason(LostReason lostReason) { this.lostReason = lostReason; }
    public void setDuplicateOfLeadId(UUID duplicateOfLeadId) { this.duplicateOfLeadId = duplicateOfLeadId; }
    public void setLastContactedAt(Instant lastContactedAt) { this.lastContactedAt = lastContactedAt; }
    public void setCreatedBy(UUID createdBy) { this.createdBy = createdBy; }
}