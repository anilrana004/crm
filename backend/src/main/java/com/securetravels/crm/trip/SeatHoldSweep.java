package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.AuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Releases provisional seats whose 2-hour hold expired without a confirmed
 * booking. Each release happens inside the batch row's PESSIMISTIC_WRITE
 * lock so seats and the derived pool stay consistent (I3).
 */
@Component
public class SeatHoldSweep {

    private static final Logger log = LoggerFactory.getLogger(SeatHoldSweep.class);

    private final SeatHoldRepository seatHolds;
    private final BatchRepository batches;
    private final AuditService auditService;

    public SeatHoldSweep(SeatHoldRepository seatHolds, BatchRepository batches, AuditService auditService) {
        this.seatHolds = seatHolds;
        this.batches = batches;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    @Transactional
    public void releaseExpired() {
        Instant now = Instant.now();
        List<SeatHold> expired = seatHolds.findByStatusAndHeldUntilBefore(SeatHold.Status.HELD, now);
        if (expired.isEmpty()) return;

        for (SeatHold hold : expired) {
            batches.findWithLockById(hold.getBatchId()).ifPresent(batch -> {
                boolean wasFull = batch.seatsAvailable() == 0;
                batch.setSeatsBooked(Math.max(0, batch.getSeatsBooked() - hold.getNumSeats()));
                if (wasFull && batch.getStatus() == Batch.Status.CLOSED) {
                    batch.setStatus(Batch.Status.OPEN);
                    auditService.statusChange("BATCH", batch.getId(), "status", "CLOSED", "OPEN");
                }
                batches.save(batch);
            });
            hold.mark(SeatHold.Status.EXPIRED);
            seatHolds.save(hold);
            log.info("[seat-hold] expired hold={} batch={} seats={}", hold.getId(), hold.getBatchId(), hold.getNumSeats());
        }
    }
}