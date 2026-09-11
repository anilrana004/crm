package com.securetravels.crm.trip;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BatchRepository extends JpaRepository<Batch, UUID> {

    List<Batch> findByTripIdOrderByDepartureDateAsc(UUID tripId);

    Optional<Batch> findByTripIdAndDepartureDate(UUID tripId, LocalDate departureDate);

    /** Serialises seat-pool writes (I3) — booking/confirm/cancel/sweep all go through this. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Batch b where b.id = :id")
    Optional<Batch> findWithLockById(@Param("id") UUID id);
}