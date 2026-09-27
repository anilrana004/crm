package com.securetravels.crm.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface TravellerChecklistRepository extends JpaRepository<TravellerChecklist, UUID> {

    Optional<TravellerChecklist> findByTravellerId(UUID travellerId);
}