package com.securetravels.crm.dashboard.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Target rows for a month plus the company-wide roll-up. */
public record TargetListResponse(
        LocalDate month,
        List<TargetDto> targets,
        Overall overall) {

    public record Overall(
            int targetBookings,
            BigDecimal targetRevenue,
            long achievedBookings,
            BigDecimal achievedRevenue,
            int bookingsPct,
            int revenuePct) {
    }
}