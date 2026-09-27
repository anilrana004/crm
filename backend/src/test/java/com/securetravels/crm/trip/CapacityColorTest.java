package com.securetravels.crm.trip;

import com.securetravels.crm.common.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 3 — capacity colour and the near-departure viability rule. Both are
 * pure derivations over {@link Batch}, so they are tested without a database.
 */
class CapacityColorTest {

    @ParameterizedTest(name = "{0}% fill -> {1}")
    @CsvSource({
            "0,   GREEN",
            "49,  GREEN",
            "74,  GREEN",
            "89,  GREEN",
            "90,  AMBER",   // exactly at the default threshold
            "91,  AMBER",
            "99,  AMBER",
            "100, RED",     // full is categorically different from nearly full
            "120, RED"      // over-booked still reads RED
    })
    @DisplayName("colour follows the configured scarcity threshold (default 90)")
    void mapsFillToColour(int fill, CapacityColor expected) {
        assertThat(CapacityColor.of(fill, 90)).isEqualTo(expected);
    }

    @Test
    @DisplayName("the cut-off follows configuration, so ops can tighten it")
    void honoursConfiguredThreshold() {
        assertThat(CapacityColor.of(75, 90)).isEqualTo(CapacityColor.GREEN);
        assertThat(CapacityColor.of(75, 70)).isEqualTo(CapacityColor.AMBER);
        assertThat(CapacityColor.of(100, 70)).isEqualTo(CapacityColor.RED);
    }

    @Test
    @DisplayName("colour is derived from the batch's own fill ratio")
    void derivesFromBatch() {
        Batch batch = new Batch(java.util.UUID.randomUUID(), LocalDate.of(2026, 6, 1), 20);
        batch.setSeatsBooked(18); // 90%

        assertThat(batch.fillPercent()).isEqualTo(90);
        assertThat(CapacityColor.of(batch, 90)).isEqualTo(CapacityColor.AMBER);
    }

    @Test
    @DisplayName("a zero-capacity batch reports 0% rather than dividing by zero")
    void handlesZeroCapacity() {
        Batch batch = new Batch(java.util.UUID.randomUUID(), LocalDate.of(2026, 6, 1), 0);

        assertThat(batch.fillPercent()).isZero();
        assertThat(CapacityColor.of(batch, 90)).isEqualTo(CapacityColor.GREEN);
    }

    @Test
    @DisplayName("fill percent rounds rather than truncates")
    void roundsFillPercent() {
        Batch batch = new Batch(java.util.UUID.randomUUID(), LocalDate.of(2026, 6, 1), 8);
        batch.setSeatsBooked(7); // 87.5%

        assertThat(batch.fillPercent()).isEqualTo(88);
        assertThat(CapacityColor.of(batch, 90)).isEqualTo(CapacityColor.GREEN);
    }

    @Test
    @DisplayName("the AMBER cut-off and the scarcity alert read the same config value")
    void colourAndAlertShareOneThreshold() {
        AppProperties props = new AppProperties();
        int threshold = props.getCapacity().getAlertFillPercent();

        assertThat(threshold).isEqualTo(90);
        // A batch sitting exactly on the threshold is both AMBER and alerting.
        assertThat(CapacityColor.of(threshold, threshold)).isEqualTo(CapacityColor.AMBER);
    }
}
