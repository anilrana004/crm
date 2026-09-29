package com.securetravels.crm.automation;

import com.securetravels.crm.automation.runtime.CronMatcher;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the six-field cron matcher behind CRON triggers (Phase 6
 * Module 2). Day-of-week uses 0 = Sunday through 7 (0..7, both allowed for
 * Sunday); the matcher normalises {@code getDayOfWeek().getValue() % 7}. When
 * BOTH day-of-month and day-of-week are restricted the standard OR rule
 * applies; a wildcard on either is a plain conjunction.
 */
class CronMatcherTest {

    @Test
    void everySecondMatchesAnything() {
        assertThat(CronMatcher.of("* * * * * *").matches(Instant.parse("2026-09-29T09:30:00Z"))).isTrue();
        assertThat(CronMatcher.of("* * * * * *").matches(Instant.parse("2026-12-31T23:59:59Z"))).isTrue();
    }

    @Test
    void everyNMinutesSkipsNonMultiples() {
        CronMatcher matcher = CronMatcher.of("0 */5 * * * *");
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:30:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:35:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:33:00Z"))).isFalse();
    }

    @Test
    void hourAndMinutePinToTheWindow() {
        CronMatcher matcher = CronMatcher.of("0 30 9 * * *");
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:30:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:31:00Z"))).isFalse();
        assertThat(matcher.matches(Instant.parse("2026-09-29T10:30:00Z"))).isFalse();
    }

    @Test
    void restrictedDayOfMonthAloneIsAConjunction() {
        CronMatcher matcher = CronMatcher.of("0 0 12 14 * *");
        assertThat(matcher.matches(Instant.parse("2026-09-14T12:00:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-15T12:00:00Z"))).isFalse();
    }

    @Test
    void restrictedBothDaysUsesTheOrRule() {
        // 13th of the month, OR any Monday (2026-09-14 is a Monday).
        CronMatcher matcher = CronMatcher.of("0 0 12 13 * 1");
        assertThat(matcher.matches(Instant.parse("2026-09-13T12:00:00Z"))).isTrue();  // 13th, Sunday
        assertThat(matcher.matches(Instant.parse("2026-09-14T12:00:00Z"))).isTrue();  // 14th, Monday
        assertThat(matcher.matches(Instant.parse("2026-09-20T12:00:00Z"))).isFalse(); // 20th, Sunday
    }

    @Test
    void monthFieldCutsOutOfWindowDayFirings() {
        CronMatcher matcher = CronMatcher.of("0 0 12 15 8 *");
        assertThat(matcher.matches(Instant.parse("2026-08-15T12:00:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-15T12:00:00Z"))).isFalse();
    }

    @Test
    void wildcardQuestionMarkIsAllowedOnlyOnTheTwoDayFields() {
        assertThat(CronMatcher.of("0 0 12 ? * *").matches(Instant.parse("2026-09-29T12:00:00Z"))).isTrue();
        assertThatThrownBy(() -> CronMatcher.of("? 0 12 ? * *"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void expressionWithWrongFieldCountIsRefused() {
        assertThatThrownBy(() -> CronMatcher.of("0 0 12 * *"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CronMatcher.of("0 0 12 * * * *"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rangesAndListsAreAccepted() {
        CronMatcher matcher = CronMatcher.of("0 15,45 9-12 * * *");
        assertThat(matcher.matches(Instant.parse("2026-09-29T09:15:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-29T11:45:00Z"))).isTrue();
        assertThat(matcher.matches(Instant.parse("2026-09-29T10:30:00Z"))).isFalse();
        assertThat(matcher.matches(Instant.parse("2026-09-29T13:15:00Z"))).isFalse();
    }
}