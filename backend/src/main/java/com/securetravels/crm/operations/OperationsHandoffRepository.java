package com.securetravels.crm.operations;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperationsHandoffRepository extends JpaRepository<OperationsHandoff, UUID> {

    Optional<OperationsHandoff> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);

    List<OperationsHandoff> findAllByOrderByCreatedAtDesc();

    /** Next sequential reference number for a given OPS-YYYY- prefix. */
    long countByOpsRefStartingWith(String prefix);
}