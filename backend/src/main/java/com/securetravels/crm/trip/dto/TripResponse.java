package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Trip;

import java.math.BigDecimal;
import java.util.UUID;

public record TripResponse(
        UUID id,
        String name,
        String slug,
        Trip.Category category,
        Trip.BookingType bookingType,
        Trip.Difficulty difficulty,
        BigDecimal baseCost,
        int durationDays,
        boolean active
) {
}