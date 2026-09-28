package com.securetravels.crm.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Trip and Batch Performance.
 *
 * <p><strong>On the money columns.</strong> There is deliberately no field called
 * "margin" here, and that is a decision rather than an omission.
 *
 * <p>{@code trips.base_cost} is the only cost figure in the schema, and it is a
 * single scalar per trip. Measured against the seeded data it behaves as a
 * per-person cost (it equals revenue-per-pax on the trips that have bookings),
 * but the seeded rows are demo fixtures, so treating them as proof of a business
 * semantic would be reading meaning into test data. The assumption is stated in
 * {@code costBasis} instead of being baked in silently.
 *
 * <p>More importantly, {@code base_cost} is not a trip's total cost. It excludes
 * hotels, transport, permits, insurance, payment fees and overhead, none of which
 * exist as data anywhere in this system. So revenue minus base_cost is a partial
 * figure with a real name: it says the revenue beat the recorded base cost by
 * this much. Presenting that as "margin" would imply a completeness the data
 * cannot support, and a reader would act on it.
 *
 * <p>True P&amp;L is Phase 9, per the Module 2 write-up.
 */
public record TripPerformanceResponse(
        String scope,
        ReportFilter filter,
        CostBasis costBasis,
        List<Trip> trips,
        Totals totals) {

    /**
     * The exact formula behind {@code revenueLessBaseCost}, returned to the
     * caller rather than hidden in the service, so nobody has to guess whether a
     * number is per-person or per-trip.
     */
    public record CostBasis(
            String field,
            boolean treatedAsPerPerson,
            boolean isPartialCost,
            String note) {
    }

    /**
     * @param fillRatePct      seatsBooked / maxCapacity across the trip's batches.
     *                         Null when no batch declares capacity, since an
     *                         uncapped trip has no fill rate rather than a 0% one.
     * @param revenueLessBaseCost revenue minus base_cost*pax. NOT a margin; see the
     *                         class note. Null when base_cost is unknown, rather
     *                         than reported as 0 which would read as "broke even".
     */
    public record Trip(
            UUID tripId,
            String name,
            int durationDays,
            long batches,
            long bookings,
            long pax,
            long seatsBooked,
            long maxCapacity,
            BigDecimal fillRatePct,
            BigDecimal revenue,
            BigDecimal avgBookingValue,
            BigDecimal revenueLessBaseCost) {
    }

    public record Totals(
            long trips,
            long bookings,
            long pax,
            BigDecimal revenue) {
    }
}
