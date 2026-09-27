package com.securetravels.crm.trip;

import com.securetravels.crm.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Module 3 — the near-departure viability sweep.
 *
 * <p>Unlike the scarcity alert (which fires the instant seats cross 90%), the
 * minimum-viable-group condition is a function of the calendar, not of an
 * event: a departure only becomes "too close to be rescued" as its date
 * approaches. Nothing triggers it, so it needs a periodic pass — same shape as
 * the existing {@link SeatHoldSweep}, and equally broker-free.
 *
 * <p>Runs hourly, not per-minute: the decision it makes (merge / re-market /
 * cancel) is a human one, and re-evaluating it 1440 times a day would not
 * change the answer. Idempotency comes from the V11 latch, so an overlapping or
 * repeated run cannot double-notify.
 */
@Component
public class CapacityAlertSweep {

    private static final Logger log = LoggerFactory.getLogger(CapacityAlertSweep.class);

    private final BatchRepository batches;
    private final TripRepository trips;
    private final CapacityAlertService alerts;
    private final AppProperties props;

    public CapacityAlertSweep(BatchRepository batches, TripRepository trips,
                              CapacityAlertService alerts, AppProperties props) {
        this.batches = batches;
        this.trips = trips;
        this.alerts = alerts;
        this.props = props;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 120_000)
    @Transactional
    public void scan() {
        LocalDate today = LocalDate.now();
        LocalDate horizon = today.plusDays(props.getCapacity().getMinGroupLeadDays());

        List<Batch> candidates = batches.findMinGroupAlertCandidates(today, horizon);
        if (candidates.isEmpty()) {
            return;
        }

        int raised = 0;
        for (Batch batch : candidates) {
            if (!alerts.isBelowMinimumGroup(batch, today)) {
                // Not at risk right now. Deliberately NOT latched: fill can still
                // grow, and if it does not, the batch keeps being re-evaluated as
                // departure nears. Latching here would silently retire a batch
                // that recovers and then falls behind again.
                continue;
            }
            UUID tripId = batch.getTripId();
            Trip trip = trips.findById(tripId).orElse(null);
            if (trip == null) {
                log.warn("[capacity] sweep: batch={} references missing trip={}", batch.getId(), tripId);
                continue;
            }
            if (alerts.onMinimumGroupRisk(batch, trip, today)) {
                raised++;
            }
        }
        if (raised > 0) {
            log.info("[capacity] sweep raised {} minimum-group alert(s) of {} candidate(s)",
                    raised, candidates.size());
        }
    }
}
