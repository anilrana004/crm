package com.securetravels.crm.document;

import com.securetravels.crm.booking.Traveller;
import com.securetravels.crm.booking.TravellerRepository;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.document.dto.ConfirmUploadRequest;
import com.securetravels.crm.document.dto.DocumentResponse;
import com.securetravels.crm.document.dto.UploadUrlRequest;
import com.securetravels.crm.document.dto.UploadUrlResponse;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * S3-backed document workflow (Phase 2 Module 1):
 *   1. staff request a presigned PUT URL (file bytes never touch this app),
 *   2. the client uploads directly to the bucket,
 *   3. staff confirm the upload; the document row is recorded and the
 *      matching checklist item moves MISSING → IN_PROGRESS.
 * Review happens on the compliance board, where an item becomes VERIFIED.
 */
@Service
public class DocumentService {

    private final DocumentRepository documents;
    private final TravellerRepository travellers;
    private final SignedUploadUrlService signer;
    private final ComplianceService compliance;
    private final AuditService auditService;
    private final AppProperties props;

    public DocumentService(DocumentRepository documents, TravellerRepository travellers,
                           SignedUploadUrlService signer, ComplianceService compliance,
                           AuditService auditService, AppProperties props) {
        this.documents = documents;
        this.travellers = travellers;
        this.signer = signer;
        this.compliance = compliance;
        this.auditService = auditService;
        this.props = props;
    }

    @Transactional(readOnly = true)
    public UploadUrlResponse requestUpload(UploadUrlRequest request, UserPrincipal caller) {
        getTraveller(request.travellerId());
        Instant now = Instant.now();
        String storageKey = StorageObjectKey.generate(request.travellerId(), request.docType(), now);
        String uploadUrl = signer.issue(storageKey, now);
        return new UploadUrlResponse(uploadUrl, storageKey, objectUrl(storageKey), signer.expiresAt(now));
    }

    @Transactional
    public DocumentResponse confirm(ConfirmUploadRequest request, UserPrincipal caller) {
        if (!StorageObjectKey.matchesTraveller(request.storageKey(), request.travellerId())) {
            throw new BadRequestException("storageKey is not a valid compliance key for this traveller");
        }
        if (StorageObjectKey.docTypeOf(request.storageKey()).orElse(null) != request.docType()) {
            throw new BadRequestException("docType does not match the storage key");
        }
        Traveller traveller = getTraveller(request.travellerId());

        Document doc = new Document(request.relatedType(), request.docType(), request.storageKey());
        doc.setTravellerId(traveller.getId());
        doc.setBookingId(traveller.getBookingId());
        doc.setMimeType(request.mimeType() == null ? "application/octet-stream" : request.mimeType());
        doc.setSizeBytes(request.sizeBytes());
        doc.setUploadedBy(caller.id());
        Document saved = documents.save(doc);
        auditService.record("DOCUMENT", saved.getId(), AuditAction.CREATE, "storage_key", null, saved.getStorageKey());

        compliance.linkDocument(traveller.getId(), request.docType(), saved.getId());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listForTraveller(java.util.UUID travellerId, UserPrincipal caller) {
        return documents.findByTravellerIdOrderByCreatedAtDesc(travellerId).stream()
                .map(this::toResponse)
                .toList();
    }

    // ------------------------------------------------------------------ internals

    private Traveller getTraveller(java.util.UUID id) {
        return travellers.findById(id).orElseThrow(() -> new NotFoundException("Traveller not found: " + id));
    }

    private String objectUrl(String storageKey) {
        return props.getStorage().getEndpoint() + "/" + props.getStorage().getBucket() + "/" + storageKey;
    }

    private DocumentResponse toResponse(Document doc) {
        return new DocumentResponse(doc.getId(), doc.getRelatedType(), doc.getTravellerId(), doc.getBookingId(),
                doc.getDocType(), doc.getStorageKey(), objectUrl(doc.getStorageKey()), doc.getMimeType(),
                doc.getSizeBytes(), doc.getUploadedBy(), doc.getCreatedAt());
    }
}