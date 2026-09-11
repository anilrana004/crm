package com.securetravels.crm.trip.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Guide record used by batch staffing and the catalogue UI. */
public record GuideResponse(
        UUID id,
        String fullName,
        String phone,
        BigDecimal dailyRate,
        boolean active,
        Instant createdAt
) {
}