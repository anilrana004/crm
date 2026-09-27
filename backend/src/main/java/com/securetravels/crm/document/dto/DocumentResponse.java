package com.securetravels.crm.document.dto;

import com.securetravels.crm.document.Document;

import java.time.Instant;
import java.util.UUID;

/** S3-backed document metadata (bytes never in Postgres). */
public record DocumentResponse(
        UUID id,
        Document.RelatedType relatedType,
        UUID travellerId,
        UUID bookingId,
        Document.DocType docType,
        String storageKey,
        String objectUrl,
        String mimeType,
        Long sizeBytes,
        UUID uploadedBy,
        Instant createdAt
) {
}