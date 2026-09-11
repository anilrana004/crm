package com.securetravels.crm.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GuideRepository extends JpaRepository<Guide, UUID> {

    List<Guide> findAllByActiveTrueOrderByFullNameAsc();

    List<Guide> findAllByOrderByFullNameAsc();
}