package com.securetravels.crm.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Team Performance, per consultant.
 *
 * <p>Revenue comes from {@code sales_commission_ledger}, so it is attributed to
 * the lead's owner as snapshotted at confirmation and is unaffected by a later
 * reassignment. Revoked credits are excluded from the totals but reported
 * separately, so a cancellation shows up as a reduction with a visible cause
 * rather than a silent edit.
 *
 * <p><strong>Deliberate divergence from the Module 4/5 dashboard.</strong>
 * {@code DashboardService.performance} also reports a per-employee revenue
 * number, computed two different ways:
 *
 * <ul>
 *   <li>it attributes bookings by {@code bookings.created_by}, which is whoever
 *       saved the booking, not the consultant who sold the trip; and</li>
 *   <li>it sums {@code payments}, which is cash received, so an unpaid confirmed
 *       booking contributes nothing and a refund contributes negatively.</li>
 * </ul>
 *
 * <p>This report uses owner-at-confirmation and net booked value. The two will
 * disagree, and that is the point: they answer different questions. The legacy
 * endpoint is left untouched so its existing consumers do not break, but this is
 * the one to use for anything commission-adjacent. Reconciling the two is a
 * deliberate follow-up, not something to change silently under existing callers.
 */
public record TeamPerformanceResponse(
        String scope,
        ReportFilter filter,
        SlaBasis slaBasis,
        List<Consultant> consultants,
        Totals totals) {

    /**
     * SLA compliance is measured only over tasks that actually have an
     * {@code sla_deadline}. Returned so a reader knows what the percentage does
     * and does not cover.
     */
    public record SlaBasis(
            boolean onlyTasksWithSlaDeadline,
            String note) {
    }

    public record Consultant(
            UUID consultantId,
            String fullName,
            String email,
            long leadsOwned,
            long leadsBooked,
            BigDecimal conversionPct,
            long bookingsCredited,
            long creditsRevoked,
            BigDecimal revenue,
            long slaTasksDue,
            long slaTasksMet,
            BigDecimal slaCompliancePct) {
    }

    public record Totals(
            long consultants,
            BigDecimal revenue,
            long creditsRevoked) {
    }
}
