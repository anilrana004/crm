package com.securetravels.crm.analytics;

/**
 * A reporting season filter.
 *
 * <p><strong>This is a business convention, not data.</strong> There is no season
 * column anywhere in the schema and none was invented, because a wrong season
 * column would look authoritative while being unbacked. Instead the filter maps
 * to a month set, and the report narrows on the month of the travel date.
 *
 * <p>Derived from Nepal trekking practice, where the four seasons differ far more
 * commercially than meteorologically:
 *
 * <ul>
 *   <li>SPRING  - Mar/Apr/May. Best trekking weather, peak pricing.</li>
 *   <li>MONSOON - Jun/Jul/Aug. Leashes rain, lowest demand.</li>
 *   <li>AUTUMN  - Sep/Oct/Nov. Second best window, peak demand.</li>
 *   <li>WINTER  - Dec/Jan/Feb. Cold but clear; mixed demand.</li>
 * </ul>
 *
 * <p>WINTER deliberately wraps the year boundary. It is a month set, not a date
 * range, so it composes with an explicit from/to instead of fighting it: a
 * December-only query and a January-only query both select WINTER.
 */
public enum Season {
    SPRING(3, 4, 5),
    MONSOON(6, 7, 8),
    AUTUMN(9, 10, 11),
    WINTER(12, 1, 2);

    private final int[] months;

    Season(int... months) {
        this.months = months;
    }

    /** 1-based calendar months, unordered as written (see the WINTER note). */
    public int[] months() {
        return months.clone();
    }

    /**
     * Boxed months, for binding to a SQL {@code IN (:param)}.
     *
     * <p>Not {@code List.of(months())}: that varargs call wraps the whole
     * {@code int[]} as a single list element, which binds as one array parameter
     * and makes {@code IN (:m)} match nothing. This is a silent wrong-answer
     * failure, not a compile error, so it is worth the extra method.
     */
    public java.util.List<Integer> monthList() {
        java.util.List<Integer> out = new java.util.ArrayList<>(months.length);
        for (int m : months) {
            out.add(m);
        }
        return out;
    }

    public boolean contains(int month) {
        for (int m : months) {
            if (m == month) return true;
        }
        return false;
    }
}
