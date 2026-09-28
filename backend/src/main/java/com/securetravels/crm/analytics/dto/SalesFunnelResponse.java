package com.securetravels.crm.analytics.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Sales Funnel.
 *
 * <p>Counts are "leads that have REACHED this stage", not "leads currently
 * sitting in it", because a snapshot of current status is not a funnel: a lead
 * that advanced to QUOTATION_SENT and then moved back to INTERESTED is still a
 * lead that reached quotation, and reporting it as a lost quotation would make
 * the conversion rate a function of how long a lead happens to have been open.
 *
 * <p>Reaching a stage is therefore the union of two sources:
 *
 * <ol>
 *   <li>the lead's current status, and</li>
 *   <li>LEAD status transitions recorded in {@code audit_log}.</li>
 * </ol>
 *
 * <p>Source 2 is what makes stage reach survive a lead moving backwards.
 */
public record SalesFunnelResponse(
        String scope,
        ReportFilter filter,
        List<Stage> stages,
        Totals totals,
        List<SourceBreakdown> bySource) {

    /**
     * @param reached       leads that reached this stage at some point
     * @param currentlyHere leads whose CURRENT status is this stage
     * @param conversionPct reached / total, as a percentage with one decimal.
     *                       Null when the denominator is zero, rather than 0.0,
     *                       because "0% of nobody converted" is a different
     *                       statement from "no leads in scope".
     */
    public record Stage(
            String code,
            String label,
            long reached,
            long currentlyHere,
            BigDecimal conversionPct) {
    }

    public record Totals(
            long leads,
            long booked,
            long lost,
            BigDecimal overallConversionPct) {
    }

    /**
     * The same funnel cut by lead source, which is the comparison the
     * {@code leads.source} column exists to support. A source that produced no
     * leads is simply absent rather than present with zeros, so the list cannot
     * imply activity that did not happen.
     */
    public record SourceBreakdown(
            String source,
            long leads,
            long reachedQuotation,
            long booked,
            BigDecimal conversionPct) {
    }
}
