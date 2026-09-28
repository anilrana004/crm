package com.securetravels.crm.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Customer Insights: repeat behaviour, lifetime value, and churn risk.
 *
 * <p><strong>Lifetime value is computed live from bookings, not read from
 * {@code customer360.total_spent}.</strong> That denormalised column is the one
 * a reader would naturally reach for, and it is wrong: on the seeded database it
 * reads 0.00 while the customer's actual net bookings are 175,000. The column is
 * maintained by {@code Customer360Service.maintainAggregates} and is evidently
 * not being kept current. Using it would have produced a customer report that
 * said every customer's lifetime value was zero, which looks like a real finding
 * and is not one.
 */
public record CustomerInsightsResponse(
        String scope,
        ReportFilter filter,
        ValueBasis valueBasis,
        Totals totals,
        List<String> topDestinations,
        List<Customer> customers) {

    /** Tells the caller the LTV number is derived, and from what. */
    public record ValueBasis(
            String source,
            boolean liveComputation,
            String note) {
    }

    /**
     * Churn is <em>risk</em>, not a prediction: a customer with no booking in the
     * window who has at least one booking before it. A model would need features
     * this system does not have (engagement, campaign response, seasonality
     * history), so this is a reproducible heuristic and the field says "reason"
     * rather than presenting a score with unexplained authority.
     */
    public record Customer(
            UUID customerId,
            String fullName,
            String email,
            String mobileNumber,
            long bookings,
            long lastBookingYear,
            BigDecimal lifetimeValue,
            Boolean repeat,
            Boolean atChurnRisk,
            String churnReason) {
    }

    public record Totals(
            long customers,
            long repeatCustomers,
            BigDecimal repeatRatePct,
            BigDecimal totalLifetimeValue,
            BigDecimal avgLifetimeValue,
            long atChurnRisk) {
    }
}
