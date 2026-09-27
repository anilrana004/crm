package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.Trip;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Full trip detail: catalogue fields + its departure batches. */
public record TripDetailResponse(
        UUID id,
        String name,
        String slug,
        Trip.Category category,
        Trip.BookingType bookingType,
        Trip.Difficulty difficulty,
        BigDecimal baseCost,
        int durationDays,
        String itinerary,
        String inclusions,
        String exclusions,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        List<BatchResponse> batches
) {
}