package com.securetravels.crm.communications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppMessageRepository extends JpaRepository<WhatsAppMessage, UUID> {

    Optional<WhatsAppMessage> findByCallbackData(String callbackData);

    Optional<WhatsAppMessage> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    List<WhatsAppMessage> findBySubjectTypeAndSubjectIdOrderByQueuedAtDesc(
            SubjectType subjectType, UUID subjectId);

    /**
     * Most recent message we sent to this number.
     *
     * <p>An inbound customer reply carries no {@code callback_data} — that token
     * only exists on our outbound messages — so the only way to attribute "what
     * did this person say" to a Lead/Booking is to follow the conversation
     * backwards from the number. The most recent outbound subject is the
     * conversation they are replying to.
     */
    Optional<WhatsAppMessage> findFirstByRecipientMobileOrderByQueuedAtDesc(String recipientMobile);

    /**
     * Atomically move QUEUED → SENDING and count the attempt; returns rows changed.
     *
     * <p>This is the single-writer guard for at-least-once delivery. Two
     * concurrent consumers (or a consumer racing its own redelivery) both
     * attempt the claim; exactly one gets 1 and is allowed to call the
     * provider, the other gets 0 and skips. A read-then-write would let both
     * observe QUEUED and double-send to a real customer.
     *
     * <p>Statuses are bound as parameters rather than written as JPQL enum
     * literals: nested-enum literal syntax differs between HQL and strict JPQL
     * parsers, and a wrong guess here is a startup-time query error.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WhatsAppMessage m
               set m.status = :sending,
                   m.attempts = m.attempts + 1,
                   m.updatedAt = CURRENT_TIMESTAMP
             where m.id = :id
               and m.status = :queued
            """)
    int claimForSending(@Param("id") UUID id,
                        @Param("queued") WhatsAppMessage.Status queued,
                        @Param("sending") WhatsAppMessage.Status sending);

    /**
     * Requeue messages stranded in SENDING by a process death mid-send.
     *
     * <p>Without this, a crash between the claim and the provider response
     * leaves a row in SENDING forever — invisible to the queue (the message was
     * already acked) and invisible to the DLQ. Re-queueing is the correct
     * recovery even though it can duplicate, because the callbackData/provider-id
     * guard makes a duplicate harmless whereas a stranded message is simply lost.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WhatsAppMessage m
               set m.status = :queued,
                   m.lastError = 'recovered: stuck in SENDING',
                   m.updatedAt = CURRENT_TIMESTAMP
             where m.status = :sending
               and m.updatedAt < :cutoff
            """)
    int recoverStuckSending(@Param("cutoff") Instant cutoff,
                            @Param("queued") WhatsAppMessage.Status queued,
                            @Param("sending") WhatsAppMessage.Status sending);

    /** Ids stranded in SENDING, so the sweep can re-route them after commit. */
    @Query("""
            select m.id from WhatsAppMessage m
             where m.status = :sending and m.updatedAt < :cutoff
            """)
    List<UUID> findStuckSendingIds(@Param("cutoff") Instant cutoff,
                                   @Param("sending") WhatsAppMessage.Status sending);
}
