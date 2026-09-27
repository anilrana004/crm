package com.securetravels.crm.trip.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Outcome of one {@code generateSeason} call.
 *
 * <p>{@code skippedDates} is reported rather than silently ignored: re-running a
 * season plan is a normal operation (the rule may have been extended), and the
 * caller needs to know which departures already existed. The unique constraint
 * on (trip_id, departure_date) is the real guard; this makes it legible.
 */
public record BatchGenerateResponse(
        UUID tripId,
        int requested,
        int created,
        int skipped,
        List<BatchResponse> batches,
        List<LocalDate> skippedDates
) {
}
