package com.securetravels.crm.document.dto;

import com.securetravels.crm.document.Document;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request a presigned S3 PUT upload URL for a traveller document. */
public record UploadUrlRequest(
        @NotNull(message = "travellerId is required")
        UUID travellerId,

        @NotNull(message = "docType is required")
        Document.DocType docType
) {
}