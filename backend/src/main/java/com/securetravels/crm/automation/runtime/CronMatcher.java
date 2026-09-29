package com.securetravels.crm.automation.runtime;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Six-field cron matching for the trigger sweep (Phase 6 Module 2).
 *
 * <p>Fields: {@code second minute hour day-of-month month day-of-week}. Only
 * the token shapes the {@code WorkflowValidator} allows are accepted:
 * {@code *}, {@code ?}, {@code *&#47;n}, {@code a-b}, {@code a-b/n},
 * {@code a,b,c}, plain values. {@code ?} is legal on the two day-of fields
 * only and means "any". When both day-of-month and day-of-week are restricted
 * the standard cron OR rule applies (fire when either matches).
 */
public final class CronMatcher {

    private final Slot seconds;
    private final Slot minutes;
    private final Slot hours;
    private final Slot dayOfMonth;
    private final Slot month;
    private final Slot dayOfWeek;

    private CronMatcher(Slot seconds, Slot minutes, Slot hours,
                        Slot dayOfMonth, Slot month, Slot dayOfWeek) {
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.dayOfMonth = dayOfMonth;
        this.month = month;
        this.dayOfWeek = dayOfWeek;
    }

    public static CronMatcher of(String expression) {
        String[] parts = expression.trim().split("\\s+");
        if (parts.length != 6) {
            throw new IllegalArgumentException(
                    "Six-field cron required, got " + parts.length + " field(s): " + expression);
        }
        return new CronMatcher(
                parse(parts[0], 0, 59, false),
                parse(parts[1], 0, 59, false),
                parse(parts[2], 0, 23, false),
                parse(parts[3], 1, 31, true),
                parse(parts[4], 1, 12, false),
                parse(parts[5], 0, 7, true));
    }

    public boolean matches(Instant at) {
        ZonedDateTime z = at.atZone(ZoneOffset.UTC);
        if (!seconds.matches(z.getSecond())) return false;
        if (!minutes.matches(z.getMinute())) return false;
        if (!hours.matches(z.getHour())) return false;
        if (!month.matches(z.getMonthValue())) return false;

        boolean domRestricted = dayOfMonth.restricted();
        boolean dowRestricted = dayOfWeek.restricted();
        boolean dayOk;
        if (domRestricted && dowRestricted) {
            dayOk = dayOfMonth.matches(z.getDayOfMonth())
                    || dayOfWeek.matches(z.getDayOfWeek().getValue() % 7);
        } else {
            dayOk = dayOfMonth.matches(z.getDayOfMonth())
                    && dayOfWeek.matches(z.getDayOfWeek().getValue() % 7);
        }
        return dayOk;
    }

    private static Slot parse(String field, int lo, int hi, boolean allowQuestion) {
        if (field.equals("*") || (allowQuestion && field.equals("?"))) {
            return Slot.any();
        }
        Set<Integer> values = new LinkedHashSet<>();
        for (String part : field.split(",")) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("Empty cron token in '" + field + "'");
            }
            int slash = part.indexOf('/');
            String base = slash >= 0 ? part.substring(0, slash) : part;
            int step = slash >= 0 ? Integer.parseInt(part.substring(slash + 1)) : 1;
            if (step <= 0) {
                throw new IllegalArgumentException("Cron step must be positive: '" + field + "'");
            }
            if (base.equals("*") || (allowQuestion && base.equals("?"))) {
                for (int v = lo; v <= hi; v += step) {
                    values.add(v);
                }
            } else {
                int dash = base.indexOf('-');
                int from = Integer.parseInt(dash >= 0 ? base.substring(0, dash) : base);
                int to = dash >= 0 ? Integer.parseInt(base.substring(dash + 1)) : from;
                if (from < lo || to > hi || from > to) {
                    throw new IllegalArgumentException("Cron value out of range in '" + field + "'");
                }
                for (int v = from; v <= to; v += step) {
                    values.add(v);
                }
            }
        }
        return Slot.of(values);
    }

    private record Slot(Set<Integer> values, boolean wildcard) {

        static Slot any() {
            return new Slot(Set.of(), true);
        }

        static Slot of(Set<Integer> values) {
            return new Slot(values, false);
        }

        boolean restricted() {
            return !wildcard;
        }

        boolean matches(int v) {
            return wildcard || values.contains(v);
        }
    }
}