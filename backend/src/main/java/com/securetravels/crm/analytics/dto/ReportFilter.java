package com.securetravels.crm.analytics.dto;

import com.securetravels.crm.analytics.Season;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The filter every Module 2 report accepts.
 *
 * <p>Every field is optional, and a report with none set returns the whole of
 * history, which is a deliberate choice to make the unfiltered case explicit
 * rather than surprising.
 *
 * <p>{@code consultantId} is a REQUEST, not a grant. {@code AnalyticsService}
 * overwrites it with the caller's own id for a SALES user, so widening the scope
 * by editing a query parameter does not work. See
 * {@code AnalyticsService#scopeToCaller}.
 */
public record ReportFilter(
        UUID consultantId,
        UUID tripId,
        LocalDate from,
        LocalDate to,
        Season season,
        String source) {

    /**
     * An empty filter, so a caller that has nothing to narrow by does not have to
     * construct nulls.
     */
    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null);
    }

    /** Half-open range: {@code [from, to)}. Inclusive of {@code from}, exclusive of {@code to}. */
    public boolean hasDateRange() {
        return from != null || to != null;
    }

    public ReportFilter withConsultant(UUID id) {
        return new ReportFilter(id, tripId, from, to, season, source);
    }
}
