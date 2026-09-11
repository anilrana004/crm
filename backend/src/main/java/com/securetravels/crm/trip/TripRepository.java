package com.securetravels.crm.trip;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TripRepository extends JpaRepository<Trip, UUID> {
    List<Trip> findAllByActiveTrueOrderByNameAsc();
    List<Trip> findAllByActiveFalseOrderByNameAsc();
    Optional<Trip> findBySlug(String slug);
}