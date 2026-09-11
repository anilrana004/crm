package com.securetravels.crm.trip.dto;

import com.securetravels.crm.trip.Batch;

import java.time.LocalDate;
import java.util.UUID;

/** Departure batch view; {@code available} is the derived seat pool (I3). */
public record BatchResponse(
        UUID id,
        UUID tripId,
        LocalDate departureDate,
        int maxCapacity,
        int seatsBooked,
        int available,
        UUID guideId,
        String guideName,
        String transportPlan,
        Batch.Status status
) {
}