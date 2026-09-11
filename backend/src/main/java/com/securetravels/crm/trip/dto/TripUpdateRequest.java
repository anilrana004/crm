package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Trip;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Partial update payload. {@code null} fields are left untouched; a non-null
 * field is applied (and audited). {@code active=false} deactivates the trip
 * so it disappears from catalogue pickers.
 */
public record TripUpdateRequest(
        @Size(max = 255, message = "name too long")
        String name,

        @Size(max = 100, message = "slug too long")
        String slug,

        Trip.Category category,
        Trip.BookingType bookingType,

        @DecimalMin(value = "0.0", message = "baseCost must not be negative")
        BigDecimal baseCost,

        @Min(value = 1, message = "durationDays must be at least 1")
        Integer durationDays,

        String itinerary,
        String inclusions,
        String exclusions,
        Boolean active
) {
}