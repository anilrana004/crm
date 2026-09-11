package com.securetravels.crm.trip.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

/** Create a departure batch on a FIXED_BATCH trip. */
public record BatchCreateRequest(
        @NotNull(message = "departureDate is required")
        LocalDate departureDate,

        @NotNull(message = "maxCapacity is required")
        @Min(value = 1, message = "maxCapacity must be at least 1")
        Integer maxCapacity,

        UUID guideId,
        String transportPlan
) {
}