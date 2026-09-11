package com.securetravels.crm.dashboard.dto;

import java.math.BigDecimal;
import java.util.List;

/** Per-employee performance line (legacy M5) plus the month totals. */
public record PerformanceResponse(
        String month,
        List<Employee> employees,
        Totals totals) {

    public record Employee(
            java.util.UUID userId,
            String fullName,
            String email,
            long leadsAssigned,
            long followUpsCompleted,
            long bookingsClosed,
            long paymentsCount,
            BigDecimal revenue) {
    }

    public record Totals(
            long leadsAssigned,
            long followUpsCompleted,
            long bookingsClosed,
            BigDecimal revenue) {
    }
}