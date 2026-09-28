package com.securetravels.crm.commission;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CommissionLedgerRepository extends JpaRepository<CommissionLedger, UUID> {

    Optional<CommissionLedger> findByBookingId(UUID bookingId);

    boolean existsByBookingId(UUID bookingId);

    List<CommissionLedger> findByConsultantIdOrderByCreditedAtDesc(UUID consultantId);

    /**
     * Credits raised in a window, for the Team Performance report.
     *
     * <p>Filtering happens here rather than in memory: the window and consultant
     * predicates are the two selective parts, and they are exactly what
     * idx_commission_ledger_consultant_credited and idx_commission_ledger_credited
     * were created to serve.
     */
    List<CommissionLedger> findByCreditedAtBetweenAndConsultantId(
            Instant from, Instant to, UUID consultantId);
}
