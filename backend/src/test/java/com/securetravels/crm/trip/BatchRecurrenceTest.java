package com.securetravels.crm.trip;

import com.securetravels.crm.trip.dto.BatchRecurrence;
import com.securetravels.crm.trip.dto.BatchRecurrence.Frequency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Module 3 — recurrence expansion is a pure date loop, so every edge case is
 * decided by the arguments alone: no clock, no database, no config.
 */
class BatchRecurrenceTest {

    @Nested
    @DisplayName("month boundaries")
    class MonthBoundaries {

        @Test
        @DisplayName("31 Jan monthly -> 28 Feb (non-leap) and clamps without drifting forward")
        void endOfMonthClampsInNonLeapYear() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 1, 31), LocalDate.of(2026, 5, 31),
                    Frequency.MONTHLY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 1, 31),
                    LocalDate.of(2026, 2, 28), // Feb 2026 has 28 days
                    LocalDate.of(2026, 3, 31), // back to the 31st, NOT 28th
                    LocalDate.of(2026, 4, 30),
                    LocalDate.of(2026, 5, 31));
        }

        @Test
        @DisplayName("same rule in a leap year lands on 29 Feb")
        void endOfMonthClampsOnLeapDayInLeapYear() {
            var r = new BatchRecurrence(
                    LocalDate.of(2028, 1, 31), LocalDate.of(2028, 3, 31),
                    Frequency.MONTHLY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2028, 1, 31),
                    LocalDate.of(2028, 2, 29), // 2028 IS a leap year
                    LocalDate.of(2028, 3, 31));
        }

        @Test
        @DisplayName("month anchoring prevents cumulative drift (the bug this guards)")
        void doesNotDriftAcrossClampedMonths() {
            // A naive `previous.plusMonths(1)` loop would give 28 Feb -> 28 Mar
            // -> 28 Apr, silently shifting the whole spring season a few days
            // earlier every time a month is short. Anchoring to firstDeparture
            // keeps every occurrence on the 31st.
            var r = new BatchRecurrence(
                    LocalDate.of(2027, 1, 31), LocalDate.of(2027, 4, 30),
                    Frequency.MONTHLY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2027, 1, 31),
                    LocalDate.of(2027, 2, 28),
                    LocalDate.of(2027, 3, 31),
                    LocalDate.of(2027, 4, 30));
        }

        @Test
        @DisplayName("crossing a year boundary is handled by date arithmetic")
        void crossesYearBoundary() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 11, 15), LocalDate.of(2027, 2, 15),
                    Frequency.MONTHLY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 11, 15),
                    LocalDate.of(2026, 12, 15),
                    LocalDate.of(2027, 1, 15),
                    LocalDate.of(2027, 2, 15));
        }

        @Test
        @DisplayName("multi-month interval skips the months in between")
        void everyNMonths() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 1, 10), LocalDate.of(2026, 12, 31),
                    Frequency.MONTHLY, 3, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 1, 10),
                    LocalDate.of(2026, 4, 10),
                    LocalDate.of(2026, 7, 10),
                    LocalDate.of(2026, 10, 10));
        }
    }

    @Nested
    @DisplayName("leap years")
    class LeapYears {

        @Test
        @DisplayName("29 Feb is a valid first departure and repeats next year")
        void leapDayAsFirstDeparture() {
            var r = new BatchRecurrence(
                    LocalDate.of(2028, 2, 29), LocalDate.of(2029, 2, 28),
                    Frequency.MONTHLY, 12, null);

            // 2029 is not a leap year, so the 12-month repeat clamps to 28 Feb.
            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2028, 2, 29),
                    LocalDate.of(2029, 2, 28));
        }

        @Test
        @DisplayName("a non-leap Feb 29 request yields only the valid first date")
        void nonLeapYearRejectsFeb29() {
            // 2027 has no 29 Feb at all, so LocalDate.of itself is the guard.
            assertThatThrownBy(() -> LocalDate.of(2027, 2, 29))
                    .isInstanceOf(java.time.DateTimeException.class);
        }

        @Test
        @DisplayName("daily recurrence walks 28 Feb -> 1 Mar across a non-leap boundary")
        void dailyAcrossNonLeapBoundary() {
            var r = new BatchRecurrence(
                    LocalDate.of(2027, 2, 27), LocalDate.of(2027, 3, 2),
                    Frequency.DAILY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2027, 2, 27),
                    LocalDate.of(2027, 2, 28),
                    LocalDate.of(2027, 3, 1),
                    LocalDate.of(2027, 3, 2));
        }

        @Test
        @DisplayName("daily recurrence includes 29 Feb in a leap year")
        void dailyAcrossLeapBoundary() {
            var r = new BatchRecurrence(
                    LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 1),
                    Frequency.DAILY, 1, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2028, 2, 28),
                    LocalDate.of(2028, 2, 29),
                    LocalDate.of(2028, 3, 1));
        }
    }

    @Nested
    @DisplayName("weekly / weekday selection")
    class Weekly {

        @Test
        @DisplayName("no weekday selection keeps the first departure's weekday")
        void keepsFirstWeekday() {
            // 2026-03-02 is a Monday; every 2 weeks -> 16 Mar, 30 Mar.
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 30),
                    Frequency.WEEKLY, 2, null);

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 3, 2),
                    LocalDate.of(2026, 3, 16),
                    LocalDate.of(2026, 3, 30));
        }

        @Test
        @DisplayName("an occurrence past lastDeparture is dropped")
        void dropsOccurrencesBeyondWindow() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 23),
                    Frequency.WEEKLY, 2, null);

            // 2026-03-30 would be the next Monday but falls outside the window.
            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 3, 2),
                    LocalDate.of(2026, 3, 16));
        }

        @Test
        @DisplayName("selected weekdays expand inside each period, ascending")
        void expandsSelectedWeekdays() {
            // Mon + Thu every week, starting Wed 2026-03-04.
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 3, 4), LocalDate.of(2026, 3, 17),
                    Frequency.WEEKLY, 1,
                    EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.THURSDAY));

            assertThat(r.dates()).containsExactly(
                    LocalDate.of(2026, 3, 5),  // Thu; that week's Mon (03-02) precedes the start
                    LocalDate.of(2026, 3, 9),  // Mon
                    LocalDate.of(2026, 3, 12), // Thu
                    LocalDate.of(2026, 3, 16)); // Mon; that week's Thu (03-19) is past the end
        }

        @Test
        @DisplayName("the first period is clipped, never emitting before firstDeparture")
        void clipsFirstPeriod() {
            // Mondays only, starting Tue 2026-03-10: the Monday of that first
            // week (03-09) is before the window and must not appear.
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 20),
                    Frequency.WEEKLY, 1,
                    Set.of(DayOfWeek.MONDAY));

            assertThat(r.dates()).containsExactly(LocalDate.of(2026, 3, 16));
        }
    }

    @Nested
    @DisplayName("validation and bounds")
    class Validation {

        @Test
        @DisplayName("a single-day window yields exactly that day")
        void singleDayWindow() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1),
                    Frequency.MONTHLY, 1, null);

            assertThat(r.dates()).containsExactly(LocalDate.of(2026, 5, 1));
        }

        @Test
        @DisplayName("lastDeparture before firstDeparture is rejected")
        void rejectsInvertedWindow() {
            assertThatThrownBy(() -> new BatchRecurrence(
                    LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1),
                    Frequency.DAILY, 1, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lastDeparture");
        }

        @Test
        @DisplayName("interval below 1 is rejected")
        void rejectsZeroInterval() {
            assertThatThrownBy(() -> new BatchRecurrence(
                    LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1),
                    Frequency.DAILY, 0, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("interval");
        }

        @Test
        @DisplayName("an unbounded rule is capped instead of hanging")
        void capsRunawayRule() {
            // 10 years of daily departures is ~3650 rows: refuse, do not build them.
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2036, 1, 1),
                    Frequency.DAILY, 1, null);

            assertThatThrownBy(r::dates)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exceeds the limit");
        }

        @Test
        @DisplayName("a rule just inside the cap is accepted")
        void allowsLargeButBoundedRule() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2027, 5, 1),
                    Frequency.DAILY, 1, null);

            List<LocalDate> dates = r.dates();
            assertThat(dates).hasSize(486);
            assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(dates.get(dates.size() - 1)).isEqualTo(LocalDate.of(2027, 5, 1));
        }

        @Test
        @DisplayName("default interval is 1 when omitted")
        void defaultsToIntervalOfOne() {
            var r = new BatchRecurrence(
                    LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 3),
                    Frequency.DAILY, null, null);

            assertThat(r.effectiveInterval()).isEqualTo(1);
            assertThat(r.dates()).hasSize(3);
        }
    }
}
