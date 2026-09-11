package com.securetravels.crm.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SeatHoldRepository extends JpaRepository<SeatHold, UUID> {

    List<SeatHold> findByBookingIdAndStatus(UUID bookingId, SeatHold.Status status);

    List<SeatHold> findByBookingIdAndStatusIn(UUID bookingId, List<SeatHold.Status> statuses);

    /** Provisional holds past their deadline — released by the sweep. */
    List<SeatHold> findByStatusAndHeldUntilBefore(SeatHold.Status status, Instant before);
}