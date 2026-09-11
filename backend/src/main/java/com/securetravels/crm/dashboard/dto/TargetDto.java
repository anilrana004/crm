package com.securetravels.crm.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** One sales-target row with live achievement & percentage progress. */
public record TargetDto(
        UUID id,
        UUID userId,
        String userFullName,
        LocalDate month,
        int targetBookings,
        BigDecimal targetRevenue,
        long achievedBookings,
        BigDecimal achievedRevenue,
        int bookingsPct,
        int revenuePct) {
}