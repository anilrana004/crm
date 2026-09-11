package com.securetravels.crm.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SalesTargetRepository extends JpaRepository<SalesTarget, UUID> {

    /** Both null-user (company-wide) rows and per-user rows resolve; null
     * parameters translate to IS NULL in the derived query. */
    Optional<SalesTarget> findByUserIdAndMonth(UUID userId, LocalDate month);

    List<SalesTarget> findByMonthOrderByUserIdAsc(LocalDate month);
}