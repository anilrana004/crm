package com.securetravels.crm.trip;

/**
 * Module 3 — traffic-light fill state for a departure batch, derived from
 * {@code seatsBooked / maxCapacity}. Presentation only: no column, no state, no
 * transition tracking. A batch is recomputed on every read, so the colour can
 * never drift from the seats it describes.
 *
 * <p>The AMBER cut-off is the same configurable threshold that drives the
 * scarcity alert, so "amber" and "we just warned ops" always mean the same
 * number. RED is reserved for a genuinely full batch: once seats_booked reaches
 * max_capacity there is nothing left to sell and the batch is effectively
 * CLOSED, which is a categorically different problem from "nearly full".
 */
public enum CapacityColor {

    GREEN,
    AMBER,
    RED;

    /**
     * @param fillPercent  {@link Batch#fillPercent()} (0-100)
     * @param amberPercent configured scarcity threshold, 1-100
     */
    public static CapacityColor of(int fillPercent, int amberPercent) {
        if (fillPercent >= 100) return RED;
        if (fillPercent >= amberPercent) return AMBER;
        return GREEN;
    }

    /** Convenience overload for the entity itself. */
    public static CapacityColor of(Batch batch, int amberPercent) {
        return of(batch.fillPercent(), amberPercent);
    }
}
