package com.securetravels.crm.document;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Per-traveller compliance checklist (one row per traveller, lazily created
 * on first view of the compliance board). Status columns mirror the board
 * tri-state defined in {@link ComplianceStatus}; document links point at
 * {@code documents} rows whose {{@link Document.RelatedType} is TRAVELLER.
 */
@Entity
@Table(name = "traveller_checklists")
@EntityListeners(AuditingEntityListener.class)
public class TravellerChecklist {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "traveller_id", nullable = false, unique = true)
    private UUID travellerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_proof_status", nullable = false, length = 20)
    private ComplianceStatus idProofStatus = ComplianceStatus.MISSING;

    @Column(name = "id_proof_document_id")
    private UUID idProofDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "medical_status", nullable = false, length = 20)
    private ComplianceStatus medicalStatus = ComplianceStatus.MISSING;

    @Column(name = "medical_document_id")
    private UUID medicalDocumentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "emergency_status", nullable = false, length = 20)
    private ComplianceStatus emergencyStatus = ComplianceStatus.MISSING;

    @Column(name = "emergency_contact_name", length = 120)
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone", length = 20)
    private String emergencyContactPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "minor_status", nullable = false, length = 20)
    private ComplianceStatus minorStatus = ComplianceStatus.MISSING;

    @Column(name = "minor_consent_document_id")
    private UUID minorConsentDocumentId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TravellerChecklist() {}

    public TravellerChecklist(UUID travellerId) {
        this.travellerId = travellerId;
    }

    public UUID getId() { return id; }
    public UUID getTravellerId() { return travellerId; }
    public ComplianceStatus getIdProofStatus() { return idProofStatus; }
    public UUID getIdProofDocumentId() { return idProofDocumentId; }
    public ComplianceStatus getMedicalStatus() { return medicalStatus; }
    public UUID getMedicalDocumentId() { return medicalDocumentId; }
    public ComplianceStatus getEmergencyStatus() { return emergencyStatus; }
    public String getEmergencyContactName() { return emergencyContactName; }
    public String getEmergencyContactPhone() { return emergencyContactPhone; }
    public ComplianceStatus getMinorStatus() { return minorStatus; }
    public UUID getMinorConsentDocumentId() { return minorConsentDocumentId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setIdProofStatus(ComplianceStatus idProofStatus) { this.idProofStatus = idProofStatus; }
    public void setIdProofDocumentId(UUID idProofDocumentId) { this.idProofDocumentId = idProofDocumentId; }
    public void setMedicalStatus(ComplianceStatus medicalStatus) { this.medicalStatus = medicalStatus; }
    public void setMedicalDocumentId(UUID medicalDocumentId) { this.medicalDocumentId = medicalDocumentId; }
    public void setEmergencyStatus(ComplianceStatus emergencyStatus) { this.emergencyStatus = emergencyStatus; }
    public void setEmergencyContactName(String emergencyContactName) { this.emergencyContactName = emergencyContactName; }
    public void setEmergencyContactPhone(String emergencyContactPhone) { this.emergencyContactPhone = emergencyContactPhone; }
    public void setMinorStatus(ComplianceStatus minorStatus) { this.minorStatus = minorStatus; }
    public void setMinorConsentDocumentId(UUID minorConsentDocumentId) { this.minorConsentDocumentId = minorConsentDocumentId; }
}
