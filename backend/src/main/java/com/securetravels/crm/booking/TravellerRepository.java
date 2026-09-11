package com.securetravels.crm.booking;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TravellerRepository extends JpaRepository<Traveller, UUID> {

    List<Traveller> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);
}