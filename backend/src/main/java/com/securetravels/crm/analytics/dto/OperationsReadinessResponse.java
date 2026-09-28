package com.securetravels.crm.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Operations Readiness.
 *
 * <p>Two of the four readiness signals exist and two do not, and the response
 * says which is which rather than shipping a uniform shape that implies equal
 * confidence across all of them.
 */
public record OperationsReadinessResponse(
        String scope,
        ReportFilter filter,
        IncidentSummary incidentSummary,
        List<BatchReadiness> batches,
        List<VendorScorecard> vendors,
        Totals totals) {

    /**
     * UNAVAILABLE, not "0 incidents".
     *
     * <p>Phase 3 Module 1 (the mobile trip log that records incidents) is not
     * built, so there is no incident table to count. Reporting zero would be a
     * fabrication: it asserts that no incident occurred on any trip, which is a
     * positive claim backed by no evidence, and it is the exact kind of green
     * dashboard that hides a missing feature until something goes wrong.
     *
     * <p>The distinction the UI must preserve: {@code available=false} means
     * nobody is watching. {@code available=true, open=0} means somebody is, and
     * they have nothing to report.
     */
    public record IncidentSummary(
            boolean available,
            String reason,
            String blockedBy,
            Long openIncidents,
            Long incidentsInPeriod) {
    }

    public record BatchReadiness(
            UUID batchId,
            UUID tripId,
            String tripName,
            String batchRef,
            Object departureDate,
            long pax,
            long seatsBooked,
            long maxCapacity,
            BigDecimal fillRatePct,
            String hotelStatus,
            String transportStatus,
            String paymentStatus,
            boolean tripSheetGenerated,
            List<String> gaps) {
    }

    /**
     * Vendor reliability derived from handoff assignment and confirmation only.
     *
     * <p>This is a <em>completion</em> score, not a reliability score, and it is
     * named accordingly. True reliability needs incident and re-work history,
     * which does not exist yet. With {@code scoreBasis} on every row the reader
     * is never left guessing which one they are looking at.
     */
    public record VendorScorecard(
            UUID vendorId,
            String vendorName,
            String category,
            String scoreBasis,
            long handoffsAssigned,
            long handoffsConfirmed,
            BigDecimal completionPct,
            boolean isReliabilityScore) {
    }

    public record Totals(
            long batches,
            long batchesWithGaps,
            long vendors,
            long handoffs) {
    }
}
