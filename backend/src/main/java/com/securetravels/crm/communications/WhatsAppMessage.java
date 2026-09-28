package com.securetravels.crm.communications;

import com.securetravels.crm.common.audit.CreatedUpdated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * One outbound WhatsApp message we intend to send — the *intent*, written
 * before the send attempt (Module 4, ADR 0005).
 *
 * <p>This row is the idempotency guard, not the provider. Interakt exposes no
 * {@code Idempotency-Key}, and a retry after a read timeout can genuinely have
 * been delivered. So we own deduplication: {@link #callbackData} carries this
 * message's id (unique-indexed), the row is claimed atomically before the HTTP
 * call, and a redelivered queue message finds a terminal status and skips.
 *
 * <p>State machine:
 * <pre>
 *   QUEUED ──claim──▶ SENDING ──ok──▶ SENT ──▶ DELIVERED ──▶ READ
 *                        │
 *                        ├── retry left ──▶ QUEUED
 *                        └── exhausted ────▶ DEAD_LETTERED
 * </pre>
 * Status updates then arrive asynchronously from Interakt's webhook
 * ({@code message_api_delivered} / {@code _read} / {@code _failed}) and only ever
 * move the state forward.
 */
@Entity
@Table(name = "whatsapp_messages")
public class WhatsAppMessage extends CreatedUpdated {

    public enum Status {
        QUEUED, SENDING, SENT, DELIVERED, READ, FAILED, DEAD_LETTERED;

        /** No further send attempt will be made from this row. */
        public boolean isTerminal() {
            return this == SENT || this == DELIVERED || this == READ
                    || this == FAILED || this == DEAD_LETTERED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "template_code", nullable = false, length = 60)
    private String templateCode;

    @Column(name = "recipient_mobile", nullable = false, length = 20)
    private String recipientMobile;

    @Column(name = "country_code", nullable = false, length = 8)
    private String countryCode;

    /** Positional {@code {{1}}..{{4}} values; null when the template takes none. */
    @Column(name = "body_values", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] bodyValues;

    @Column(name = "callback_data", length = 512)
    private String callbackData;

    @Column(nullable = false, length = 30)
    private String provider = "INTERAKT";

    @Column(name = "provider_message_id", length = 80)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "channel_error_code", length = 20)
    private String channelErrorCode;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "read_at")
    private Instant readAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected WhatsAppMessage() {
    }

    public static WhatsAppMessage queued(SubjectType subjectType, UUID subjectId, String templateCode,
                                         String recipientMobile, String countryCode, List<String> bodyValues) {
        WhatsAppMessage m = new WhatsAppMessage();
        m.subjectType = subjectType;
        m.subjectId = subjectId;
        m.templateCode = templateCode;
        m.recipientMobile = recipientMobile;
        m.countryCode = countryCode;
        m.bodyValues = bodyValues == null || bodyValues.isEmpty()
                ? null
                : bodyValues.toArray(new String[0]);
        // Correlation key. Interakt echoes callbackData back in every status
        // webhook (data.message.meta_data.source_data.callback_data), which
        // turns webhook handling into a local unique-index lookup instead of a
        // phone-number match. A dedicated token rather than the row id because
        // the id is only assigned on persist — a factory cannot read it.
        m.callbackData = "st-" + UUID.randomUUID();
        return m;
    }

    // ---- state transitions (no setters: the machine is the API) ----

    public void markSent(String providerMessageId, Instant at) {
        this.providerMessageId = providerMessageId;
        this.status = Status.SENT;
        this.sentAt = at;
        this.lastError = null;
    }

    public void markDelivered(Instant at) {
        if (this.status == Status.READ) return;   // never move backwards
        this.status = Status.DELIVERED;
        if (this.deliveredAt == null) this.deliveredAt = at;
    }

    public void markRead(Instant at) {
        this.status = Status.READ;
        if (this.deliveredAt == null) this.deliveredAt = at;
        this.readAt = at;
    }

    public void markFailed(String error, String channelErrorCode, String reason) {
        this.status = Status.FAILED;
        this.lastError = truncate(error, 500);
        this.channelErrorCode = truncate(channelErrorCode, 20);
        this.failureReason = truncate(reason, 300);
    }

    public void markDeadLettered(String error) {
        this.status = Status.DEAD_LETTERED;
        this.lastError = truncate(error, 500);
    }

    /**
     * Back to {@code QUEUED} after a failed-but-retryable attempt, so either the
     * inline retry loop or the container's retry advice can pick it up. The
     * error is retained because the last thing that went wrong is the first
     * thing worth reading in the DLQ.
     */
    public void markRetry(String error) {
        this.status = Status.QUEUED;
        this.lastError = truncate(error, 500);
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    // ---- accessors ----

    public UUID getId() { return id; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getTemplateCode() { return templateCode; }
    public String getRecipientMobile() { return recipientMobile; }
    public String getCountryCode() { return countryCode; }
    public String getCallbackData() { return callbackData; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getChannelErrorCode() { return channelErrorCode; }
    public String getFailureReason() { return failureReason; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getReadAt() { return readAt; }

    public List<String> bodyValues() {
        return bodyValues == null ? List.of() : Arrays.asList(bodyValues);
    }

    /**
     * Package-private test seam for the attempt counter.
     *
     * <p>In production the counter is advanced by the atomic
     * {@code claimForSending} bulk UPDATE, so a unit test that mocks the
     * repository has no other way to stage a row that has already been
     * attempted N times — which is precisely the state the exhaustion branch
     * needs to be tested against.
     */
    void attemptsForTest(int attempts) {
        this.attempts = attempts;
    }
}
