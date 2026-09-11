package com.securetravels.crm.operations.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OpsNoteRequest(
        @NotBlank(message = "note is required")
        @Size(max = 2000, message = "note must be 2000 characters or fewer")
        String note) {
}