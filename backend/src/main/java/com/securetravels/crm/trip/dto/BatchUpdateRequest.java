package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Batch;
import jakarta.validation.constraints.Min;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Partial batch update. {@code null} fields are left untouched; pass
 * {@code guideId} to (re)assign a guide or {@code unassignGuide=true} to
 * clear it.
 */
public record BatchUpdateRequest(
        LocalDate departureDate,

        @Min(value = 1, message = "maxCapacity must be at least 1")
        Integer maxCapacity,

        UUID guideId,
        Boolean unassignGuide,
        String transportPlan,
        Batch.Status status
) {
}