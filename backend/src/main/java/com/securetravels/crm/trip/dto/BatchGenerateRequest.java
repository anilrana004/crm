package com.securetravels.crm.trip.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Module 3 — bulk-create a season of departure batches for a FIXED_BATCH trip
 * from a recurrence rule. Every generated date shares one capacity, guide and
 * transport plan; per-batch overrides afterwards are ordinary PATCH calls.
 */
public record BatchGenerateRequest(
        @NotNull(message = "recurrence is required")
        @Valid
        BatchRecurrence recurrence,

        @NotNull(message = "maxCapacity is required")
        @Min(value = 1, message = "maxCapacity must be at least 1")
        Integer maxCapacity,

        UUID guideId,
        String transportPlan
) {
}
