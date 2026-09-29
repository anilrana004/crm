package com.securetravels.crm.communications.email;

import com.securetravels.crm.communications.SubjectType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    Optional<EmailMessage> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    /**
     * Outbound email for one subject, newest first.
     *
     * <p>Scoped by subject for the same reason {@code WhatsAppMessageRepository}
     * is: this backs a per-customer history view, and a global "all email" list
     * would hand every authenticated user every customer's address.
     */
    List<EmailMessage> findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc(SubjectType subjectType, UUID subjectId);

    @Query("select m from EmailMessage m where m.status = :status and m.queuedAt <= :now order by m.queuedAt asc")
    Page<EmailMessage> findDue(@Param("status") EmailMessage.Status status, @Param("now") Instant now,
                                Pageable pageable);

    @Query("""
            select m from EmailMessage m
             where (:status     is null or m.status     = :status)
               and (:recipient is null or lower(m.recipientEmail) = :recipient)
             order by m.queuedAt desc
            """)
    Page<EmailMessage> search(@Param("status") EmailMessage.Status status,
                              @Param("recipient") String recipient,
                              Pageable pageable);
}
