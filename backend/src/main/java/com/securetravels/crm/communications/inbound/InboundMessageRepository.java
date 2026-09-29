package com.securetravels.crm.communications.inbound;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InboundMessageRepository extends JpaRepository<InboundMessage, UUID> {

    /**
     * The idempotency check. A gateway retry of one message resolves here and
     * must produce no second row — and, downstream, no second lead.
     */
    Optional<InboundMessage> findByProviderAndProviderMessageId(String provider, String providerMessageId);

    boolean existsByProviderAndProviderMessageId(String provider, String providerMessageId);

    /**
     * Atomically claim the right to be the one delivery of this provider id.
     *
     * <p>Written as {@code INSERT ... ON CONFLICT DO NOTHING} rather than a
     * {@code save()} inside a try/catch for one specific reason: a unique
     * violation poisons the surrounding transaction in Hibernate, so catching
     * it and continuing would fail at commit with
     * {@code UnexpectedRollbackException} — the duplicate would still be
     * reported as an error to the gateway, which would then retry harder. This
     * statement simply reports 0 rows for a duplicate and leaves the
     * transaction healthy, so the caller can carry on and resolve the sender.
     *
     * <p>Also removes the check-then-act window that let two concurrent
     * deliveries of one message both pass the {@code exists} probe.
     *
     * @return 1 if this call inserted the row, 0 if another delivery got there first
     */
    @Modifying
    @Query(value = """
            INSERT INTO inbound_messages
                (id, channel, provider, provider_message_id, from_mobile, from_email,
                 body, is_media, media_type, received_at, created_at, version)
            VALUES
                (:id, :channel, :provider, :providerMessageId, :fromMobile, :fromEmail,
                 :body, :isMedia, :mediaType, :receivedAt, now(), 0)
            ON CONFLICT (provider, provider_message_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id,
                       @Param("channel") String channel,
                       @Param("provider") String provider,
                       @Param("providerMessageId") String providerMessageId,
                       @Param("fromMobile") String fromMobile,
                       @Param("fromEmail") String fromEmail,
                       @Param("body") String body,
                       @Param("isMedia") boolean isMedia,
                       @Param("mediaType") String mediaType,
                       @Param("receivedAt") Instant receivedAt);
}

