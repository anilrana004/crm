package com.securetravels.crm.communications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimelineEventRepository extends JpaRepository<TimelineEvent, UUID> {

    List<TimelineEvent> findBySubjectTypeAndSubjectIdOrderByCreatedAtDescSeqDesc(
            SubjectType subjectType, UUID subjectId);

    /**
     * Reverse lookup for an inbound customer message: we know the provider's
     * message id but not which of the (up to three) timelines for that person
     * should carry it.
     */
    List<TimelineEvent> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    boolean existsByProviderAndProviderMessageIdAndKind(
            String provider, String providerMessageId, TimelineEvent.Kind kind);
}
