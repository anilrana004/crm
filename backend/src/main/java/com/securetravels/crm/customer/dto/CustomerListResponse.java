package com.securetravels.crm.customer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Row for the /api/customers list (no per-trip breakdown — light payload). */
public record CustomerListResponse(
        UUID id,
        String fullName,
        String mobileNumber,
        String email,
        boolean marketingOptIn,
        boolean consentGiven,
        int totalTrips,
        LocalDate lastTripDate,
        BigDecimal totalSpent,
        String suggestOffer,
        String[] offerTags,
        String notes,
        Instant createdAt
) {
}
