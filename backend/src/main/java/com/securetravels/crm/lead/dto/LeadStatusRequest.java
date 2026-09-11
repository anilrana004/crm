package com.securetravels.crm.lead.dto;

import com.securetravels.crm.lead.Lead;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LeadStatusRequest(
        @NotNull(message = "status is required")
        Lead.Status status,

        Lead.LostReason lostReason,

        @Size(max = 1000)
        String note
) {
}