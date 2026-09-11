package com.securetravels.crm.payment;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentReminderPolicyTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 10);

    @Test
    void dueTodayIsWithinHorizon() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY, TODAY, 3)).isTrue();
    }

    @Test
    void dueUpToThreeDaysAheadIsWithinHorizon() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.plusDays(1), TODAY, 3)).isTrue();
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.plusDays(2), TODAY, 3)).isTrue();
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.plusDays(3), TODAY, 3)).isTrue();
    }

    @Test
    void dueFourDaysOutIsOutsideHorizon() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.plusDays(4), TODAY, 3)).isFalse();
    }

    @Test
    void overdueLineIsStillReminded() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.minusDays(2), TODAY, 3)).isTrue();
    }

    @Test
    void nullDueDateIsNeverWithinHorizon() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(null, TODAY, 3)).isFalse();
    }

    @Test
    void zeroHorizonRemindsOnlyTodays() {
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY, TODAY, 0)).isTrue();
        assertThat(PaymentReminderPolicy.isDueWithinHorizon(TODAY.plusDays(1), TODAY, 0)).isFalse();
    }

    @Test
    void labelIsTodayOrHumanReadableDays() {
        assertThat(PaymentReminderPolicy.dueLabel(null, TODAY)).isEqualTo("today");
        assertThat(PaymentReminderPolicy.dueLabel(TODAY, TODAY)).isEqualTo("today");
        assertThat(PaymentReminderPolicy.dueLabel(TODAY.minusDays(3), TODAY)).isEqualTo("today");
        assertThat(PaymentReminderPolicy.dueLabel(TODAY.plusDays(5), TODAY)).isEqualTo("5 day(s)");
    }
}