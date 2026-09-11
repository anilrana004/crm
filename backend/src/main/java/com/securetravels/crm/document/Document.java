package com.securetravels.crm.document;

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

import java.util.UUID;

/**
 * S3 bridge for ID proofs / medical certificates / trip photos. The bucket
 * object key is stored; file bytes never enter Postgres. Uploads happen via
 * presigned URLs (Phase-1 wiring pending docs).
 */
@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_related", columnList = "related_type")
})
public class Document extends CreatedOnly {

    public enum RelatedType { TRAVELLER, BOOKING, LEAD }
    public enum DocType { ID_PROOF, MEDICAL_CERT, TRIP_PHOTO }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "related_type", nullable = false, length = 20)
    private RelatedType relatedType;

    @Column(name = "traveller_id")
    private UUID travellerId;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 20)
    private DocType docType;

    @Column(name = "storage_key", nullable = false, length = 300)
    private String storageKey;

    @Column(name = "mime_type", length = 120)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    protected Document() {}

    public Document(RelatedType relatedType, DocType docType, String storageKey) {
        this.relatedType = relatedType;
        this.docType = docType;
        this.storageKey = storageKey;
    }

    public UUID getId() { return id; }
    public RelatedType getRelatedType() { return relatedType; }
    public UUID getTravellerId() { return travellerId; }
    public UUID getBookingId() { return bookingId; }
    public DocType getDocType() { return docType; }
    public String getStorageKey() { return storageKey; }
    public String getMimeType() { return mimeType; }
    public Long getSizeBytes() { return sizeBytes; }
    public UUID getUploadedBy() { return uploadedBy; }

    public void setTravellerId(UUID travellerId) { this.travellerId = travellerId; }
    public void setBookingId(UUID bookingId) { this.bookingId = bookingId; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public void setUploadedBy(UUID uploadedBy) { this.uploadedBy = uploadedBy; }
}