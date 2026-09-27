package com.securetravels.crm.document.dto;

import java.util.List;
import java.util.UUID;

/** Per-traveller compliance board row. */
public record TravellerComplianceResponse(
        UUID travellerId,
        String fullName,
        Integer age,
        UUID bookingId,
        UUID tripId,
        int requiredCount,
        int verifiedCount,
        int compliancePercent,
        String color,
        List<ComplianceItemView> items
) {
    public boolean fullyCompliant() {
        return requiredCount > 0 && verifiedCount == requiredCount;
    }
}