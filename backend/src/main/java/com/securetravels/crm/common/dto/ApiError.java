package com.securetravels.crm.common.dto;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        List<FieldError> fieldErrors
) {
    public ApiError(int status, String code, String message) {
        this(Instant.now(), status, code, message, List.of());
    }

    public ApiError(int status, String code, String message, List<FieldError> fieldErrors) {
        this(Instant.now(), status, code, message, fieldErrors);
    }

    public record FieldError(String field, String message) {}
}