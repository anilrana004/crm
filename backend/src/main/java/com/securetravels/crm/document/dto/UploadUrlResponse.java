package com.securetravels.crm.document.dto;

import java.time.Instant;

/** Response from an upload-URL request. PUT the file bytes to {@code uploadUrl}. */
public record UploadUrlResponse(
        String uploadUrl,
        String storageKey,
        String objectUrl,
        Instant expiresAt
) {
}