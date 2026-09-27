package com.securetravels.crm.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByTravellerIdOrderByCreatedAtDesc(UUID travellerId);

    List<Document> findByBookingIdOrderByCreatedAtDesc(UUID bookingId);

    List<Document> findByRelatedTypeAndTravellerId(Document.RelatedType relatedType, UUID travellerId);
}