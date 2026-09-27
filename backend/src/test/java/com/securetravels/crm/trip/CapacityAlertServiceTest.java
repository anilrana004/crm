package com.securetravels.crm.trip;

import com.securetravels.crm.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 3 — the near-departure viability predicate. {@code today} is a
 * parameter rather than a clock read, so every case is deterministic.
 */
class CapacityAlertServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 26);

    private CapacityAlertService serviceWith(int minGroupSize, int minGroupFillPercent, int leadDays) {
        AppProperties props = new AppProperties();
        props.getCapacity().setMinGroupSize(minGroupSize);
        props.getCapacity().setMinGroupFillPercent(minGroupFillPercent);
        props.getCapacity().setMinGroupLeadDays(leadDays);
        // Notification/email collaborators are irrelevant to the pure predicate.
        return new CapacityAlertService(null, null, null, null, props);
    }

    private Batch batch(LocalDate departure, int max, int booked, Batch.Status status) {
        Batch b = new Batch(UUID.randomUUID(), departure, max);
        b.setSeatsBooked(booked);
        b.setStatus(status);
        return b;
    }

    private CapacityAlertService service() {
        return serviceWith(6, 50, 21);
    }

    @Test
    @DisplayName("a near-departure batch under the headcount floor is at risk")
    void alertsOnThinGroup() {
        // 14 days out, 3 of 20 booked: below both the 6-person and 50% bars.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(14), 20, 3, Batch.Status.OPEN), TODAY)).isTrue();
    }

    @Test
    @DisplayName("a healthy near-departure batch is left alone")
    void ignoresHealthyBatch() {
        // 14 days out, 15 of 20 booked: 75% fill, above the 6-person floor.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(14), 20, 15, Batch.Status.OPEN), TODAY)).isFalse();
    }

    @Test
    @DisplayName("a far-future thin batch is not yet at risk")
    void ignoresDistantBatch() {
        // 60 days out is beyond the 21-day lead window: too early to panic.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(60), 20, 1, Batch.Status.OPEN), TODAY)).isFalse();
    }

    @Test
    @DisplayName("departing today is still inside the window")
    void includesDepartureToday() {
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY, 20, 0, Batch.Status.OPEN), TODAY)).isTrue();
    }

    @Test
    @DisplayName("the boundary day exactly at leadDays is included")
    void includesBoundaryDay() {
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(21), 20, 0, Batch.Status.OPEN), TODAY)).isTrue();
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(22), 20, 0, Batch.Status.OPEN), TODAY)).isFalse();
    }

    @Test
    @DisplayName("a past departure is not re-alerted")
    void ignoresPastDeparture() {
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.minusDays(1), 20, 0, Batch.Status.OPEN), TODAY)).isFalse();
    }

    @Test
    @DisplayName("the percentage floor catches a large coach that just meets the headcount")
    void catchesLowFillOnLargeCoach() {
        // 100 seats, 6 booked: meets minGroupSize=6 exactly, but 6% fill is hopeless.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(10), 100, 6, Batch.Status.OPEN), TODAY)).isTrue();
    }

    @Test
    @DisplayName("the headcount floor catches a small group on an empty large coach")
    void catchesThinGroupOnLargeCoach() {
        // 100 seats, 40 booked: 40% fill is below 50%, and 40 >= 6, so this
        // trips purely on the percentage rule.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(10), 100, 40, Batch.Status.OPEN), TODAY)).isTrue();
    }

    @Test
    @DisplayName("a nearly-sold-out big coach is not at risk")
    void ignoresNearlyFullBigCoach() {
        // 100 seats, 60 booked: headcount met, 60% fill clears the bar.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(10), 100, 60, Batch.Status.OPEN), TODAY)).isFalse();
    }

    @Test
    @DisplayName("closed and cancelled batches are not escalated")
    void skipsNonOpenBatches() {
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(5), 20, 0, Batch.Status.CLOSED), TODAY)).isFalse();
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(5), 20, 0, Batch.Status.CANCELLED), TODAY)).isFalse();
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(5), 20, 0, Batch.Status.READY_FOR_DEPARTURE), TODAY)).isFalse();
    }

    @Test
    @DisplayName("an empty near-departure departure is at risk under the default config")
    void emptyDepartureIsAtRisk() {
        // 7 days out, nobody booked: 0% fill and 0 < 6 travellers.
        assertThat(service().isBelowMinimumGroup(
                batch(TODAY.plusDays(7), 10, 0, Batch.Status.OPEN), TODAY)).isTrue();
    }

    @Test
    @DisplayName("thresholds are configurable, not hard-coded")
    void thresholdsAreConfigurable() {
        // A 40-seat premium trip wanting 20 people and 80% fill.
        var strict = serviceWith(20, 80, 7);
        assertThat(strict.isBelowMinimumGroup(
                batch(TODAY.plusDays(5), 40, 15, Batch.Status.OPEN), TODAY)).isTrue();
        assertThat(strict.isBelowMinimumGroup(
                batch(TODAY.plusDays(5), 40, 35, Batch.Status.OPEN), TODAY)).isFalse();
    }
}
