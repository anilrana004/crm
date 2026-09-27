package com.securetravels.crm.trip.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Module 3 — a plain date-iteration recurrence rule describing when a season's
 * departures run. Deliberately NOT iCal/RRULE: the whole feature is "walk dates
 * between two bounds", and a hand-rolled loop is auditable and testable.
 *
 * <p><b>Month anchoring (the subtle part).</b> Monthly occurrences are computed
 * as {@code firstDeparture.plusMonths(n * interval)} — always measured from the
 * ORIGINAL first departure, never from the previously emitted date. Iterating
 * cumulatively would let a clamped day-of-month drift forward: 31 Jan + 1 month
 * clamps to 28 Feb, and 28 Feb + 1 month lands on 28 Mar, silently moving every
 * later departure a few days earlier. Anchoring keeps 31 Jan -> 28 Feb -> 31 Mar.
 * Leap years fall out of {@link LocalDate} arithmetic for free: the same rule
 * yields 28 Feb in 2026 and 29 Feb in 2028.
 *
 * @param firstDeparture first departure date (inclusive)
 * @param lastDeparture  last departure date (inclusive)
 * @param frequency      period unit
 * @param interval       repeat every N periods; must be >= 1
 * @param weekdays       WEEKLY only: which days inside each period to depart on.
 *                       Empty/null means "keep the first departure's weekday".
 */
public record BatchRecurrence(
        @NotNull(message = "firstDeparture is required")
        LocalDate firstDeparture,

        @NotNull(message = "lastDeparture is required")
        LocalDate lastDeparture,

        @NotNull(message = "frequency is required")
        Frequency frequency,

        @Min(value = 1, message = "interval must be at least 1")
        Integer interval,

        @Size(max = 7, message = "weekdays may contain at most 7 entries")
        Set<DayOfWeek> weekdays
) {

    /** Period unit for the recurrence. */
    public enum Frequency {
        /** Every {@code interval} days. */
        DAILY,
        /** Every {@code interval} weeks; see {@code weekdays}. */
        WEEKLY,
        /** Every {@code interval} months, day-of-month anchored to the first departure. */
        MONTHLY
    }

    /** Hard ceiling on generated occurrences, so a typo cannot spin forever. */
    public static final int MAX_OCCURRENCES = 500;

    public BatchRecurrence {
        if (interval != null && interval < 1) {
            throw new IllegalArgumentException("interval must be at least 1");
        }
        if (firstDeparture != null && lastDeparture != null && lastDeparture.isBefore(firstDeparture)) {
            throw new IllegalArgumentException("lastDeparture must not be before firstDeparture");
        }
        if (frequency == null && (firstDeparture != null && lastDeparture != null)) {
            throw new IllegalArgumentException("frequency is required");
        }
    }

    public int effectiveInterval() {
        return interval == null ? 1 : interval;
    }

    /**
     * Expands the rule into concrete departure dates, ascending and de-duplicated.
     * Pure: no clock, no database, no configuration — every edge case here is
     * decided by the arguments alone.
     *
     * @throws IllegalArgumentException if the rule is malformed or would produce
     *                                  more than {@link #MAX_OCCURRENCES} dates
     */
    public List<LocalDate> dates() {
        if (firstDeparture == null || lastDeparture == null || frequency == null) {
            throw new IllegalArgumentException("firstDeparture, lastDeparture and frequency are required");
        }
        if (lastDeparture.isBefore(firstDeparture)) {
            throw new IllegalArgumentException("lastDeparture must not be before firstDeparture");
        }
        int step = effectiveInterval();
        List<LocalDate> out = switch (frequency) {
            case DAILY -> daily(step);
            case WEEKLY -> weekly(step);
            case MONTHLY -> monthly(step);
        };
        if (out.size() > MAX_OCCURRENCES) {
            throw new IllegalArgumentException("recurrence would generate " + out.size()
                    + " departures, which exceeds the limit of " + MAX_OCCURRENCES
                    + "; use a longer interval or a shorter season");
        }
        return out;
    }

    private List<LocalDate> daily(int step) {
        List<LocalDate> out = new ArrayList<>();
        // Bounded by date arithmetic rather than a counter so the loop is provably finite.
        for (int n = 0; n <= MAX_OCCURRENCES; n++) {
            LocalDate d = firstDeparture.plusDays((long) n * step);
            if (d.isAfter(lastDeparture)) break;
            out.add(d);
        }
        return out;
    }

    private List<LocalDate> weekly(int step) {
        List<LocalDate> out = new ArrayList<>();
        Set<DayOfWeek> days = normaliseWeekdays();
        if (days.isEmpty()) {
            // No weekday selection: keep firstDeparture's own weekday, every N weeks.
            for (int n = 0; n <= MAX_OCCURRENCES; n++) {
                LocalDate d = firstDeparture.plusWeeks((long) n * step);
                if (d.isAfter(lastDeparture)) break;
                out.add(d);
            }
            return out;
        }
        // Monday of the week containing firstDeparture; DayOfWeek is Mon=1..Sun=7.
        LocalDate weekStart = firstDeparture.minusDays(firstDeparture.getDayOfWeek().getValue() - 1L);
        for (int n = 0; n <= MAX_OCCURRENCES; n++) {
            LocalDate ws = weekStart.plusWeeks((long) n * step);
            if (ws.isAfter(lastDeparture)) break;
            for (DayOfWeek dow : days) {
                LocalDate d = ws.plusDays(dow.getValue() - 1L);
                // The first period is clipped so we never emit before firstDeparture.
                if (!d.isBefore(firstDeparture) && !d.isAfter(lastDeparture)) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private List<LocalDate> monthly(int step) {
        List<LocalDate> out = new ArrayList<>();
        for (int n = 0; n <= MAX_OCCURRENCES; n++) {
            // Anchored to firstDeparture every time — see the class javadoc on drift.
            LocalDate d = firstDeparture.plusMonths((long) n * step);
            if (d.isAfter(lastDeparture)) break;
            out.add(d);
        }
        return out;
    }

    /** Ascending, immutable, null-safe. */
    private Set<DayOfWeek> normaliseWeekdays() {
        if (weekdays == null || weekdays.isEmpty()) {
            return Set.of();
        }
        return new TreeSet<>(EnumSet.copyOf(weekdays));
    }
}
