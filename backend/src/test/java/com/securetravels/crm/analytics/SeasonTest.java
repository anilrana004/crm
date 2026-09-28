package com.securetravels.crm.analytics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SeasonTest {

    @Test
    @DisplayName("each month maps to exactly one season")
    void everyMonthBelongsToExactlyOneSeason() {
        for (int month = 1; month <= 12; month++) {
            int matches = 0;
            for (Season s : Season.values()) {
                if (s.contains(month)) matches++;
            }
            assertThat(matches)
                    .as("month %d matched %d seasons; a month in two seasons would double count", month, matches)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("the four seasons cover the whole year")
    void allMonthsCovered() {
        for (int month = 1; month <= 12; month++) {
            boolean covered = false;
            for (Season s : Season.values()) {
                covered |= s.contains(month);
            }
            assertThat(covered).as("month %d is in no season", month).isTrue();
        }
    }

    @Test
    @DisplayName("WINTER wraps the year boundary")
    void winterWrapsYear() {
        assertThat(Season.WINTER.contains(12)).isTrue();
        assertThat(Season.WINTER.contains(1)).isTrue();
        assertThat(Season.WINTER.contains(2)).isTrue();
        // Guard against someone "tidying" the order into 1,2,12 and breaking the
        // documented wrap semantics.
        assertThat(Season.WINTER.months()).containsExactlyInAnyOrder(12, 1, 2);
    }

    @Test
    @DisplayName("monthList boxes each month, it does not wrap the array")
    void monthListIsBoxed() {
        // List.of(int[]) would produce a one-element list holding the array, which
        // binds as a single parameter and makes IN (:m) match nothing. The failure
        // is silent, so it is asserted explicitly.
        List<Integer> months = Season.AUTUMN.monthList();
        assertThat(months).containsExactly(9, 10, 11);
        assertThat(months).hasSize(Season.AUTUMN.months().length);
        for (Object m : months) {
            assertThat(m).isInstanceOf(Integer.class);
        }
    }

    @Test
    @DisplayName("months() hands out a copy, so a caller cannot corrupt the enum")
    void monthsIsDefensivelyCopied() {
        int[] first = Season.SPRING.months();
        first[0] = 99;
        assertThat(Season.SPRING.months()).containsExactly(3, 4, 5);
    }
}
