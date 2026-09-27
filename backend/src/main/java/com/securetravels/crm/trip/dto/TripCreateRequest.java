package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Trip;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Trip catalogue creation payload (Admin/Manager). */
public record TripCreateRequest(
        @NotBlank(message = "name is required")
        @Size(max = 255, message = "name too long")
        String name,

        @Size(max = 100, message = "slug too long")
        String slug,

        @NotNull(message = "category is required")
        Trip.Category category,

        @NotNull(message = "bookingType is required")
        Trip.BookingType bookingType,

        Trip.Difficulty difficulty,

        @NotNull(message = "baseCost is required")
        @DecimalMin(value = "0.0", message = "baseCost must not be negative")
        BigDecimal baseCost,

        @NotNull(message = "durationDays is required")
        @Min(value = 1, message = "durationDays must be at least 1")
        Integer durationDays,

        String itinerary,
        String inclusions,
        String exclusions
) {
}