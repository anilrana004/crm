package com.securetravels.crm.document.dto;

import com.securetravels.crm.document.Document;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Confirm that a key obtained via upload-url was successfully uploaded. */
public record ConfirmUploadRequest(
        @NotBlank(message = "storageKey is required")
        String storageKey,

        @NotNull(message = "relatedType is required")
        Document.RelatedType relatedType,

        @NotNull(message = "docType is required")
        Document.DocType docType,

        @NotNull(message = "travellerId is required")
        UUID travellerId,

        String mimeType,
        Long sizeBytes
) {
}