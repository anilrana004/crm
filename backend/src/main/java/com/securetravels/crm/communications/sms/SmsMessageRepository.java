package com.securetravels.crm.communications.sms;

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
public interface SmsMessageRepository extends JpaRepository<SmsMessage, UUID> {

    Optional<SmsMessage> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    /**
     * Outbound SMS for one subject, newest first.
     *
     * <p>Scoped by subject for the same reason as
     * {@code EmailMessageRepository.findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc}:
     * a per-customer history view, not a global message dump.
     */
    List<SmsMessage> findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc(SubjectType subjectType, UUID subjectId);

    @Query("select m from SmsMessage m where m.status = :status and m.queuedAt <= :now order by m.queuedAt asc")
    Page<SmsMessage> findDue(@Param("status") SmsMessage.Status status, @Param("now") Instant now,
                              Pageable pageable);

    /**
     * Most recent message we sent to a number, used to attribute an inbound
     * reply to the right conversation. An inbound message from a number we have
     * never texted has no owner here, and the caller falls back to a
     * customer360 lookup.
     */
    @Query("select m from SmsMessage m where m.recipientMobile = :digits order by m.queuedAt desc")
    Optional<SmsMessage> findLatestToNumber(@Param("digits") String digits);
}
