package com.securetravels.crm.document.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Per-batch compliance aggregation. */
public record BatchComplianceResponse(
        UUID batchId,
        UUID tripId,
        String tripName,
        LocalDate departureDate,
        int readyThresholdPercent,
        int totalRequired,
        int totalVerified,
        int compliancePercent,
        String color,
        boolean readyForDeparture,
        int remainingItems,
        List<TravellerComplianceResponse> travellers
) {
}