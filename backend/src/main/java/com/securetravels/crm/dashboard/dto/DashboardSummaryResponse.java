package com.securetravels.crm.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Daily/monthly sales-dashboard cards (Module 4). */
public record DashboardSummaryResponse(
        String period,
        java.time.LocalDate from,
        long totalLeads,
        long newLeads,
        long followUpDue,
        long interested,
        long quotationSent,
        long bookingConfirmed,
        long lost,
        long newBookings,
        long confirmedBookings,
        BigDecimal revenue) {
}