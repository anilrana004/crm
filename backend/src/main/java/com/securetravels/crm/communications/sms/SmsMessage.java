package com.securetravels.crm.communications.sms;

import com.securetravels.crm.common.audit.CreatedUpdated;
import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.thread.CommunicationThread;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * One outbound SMS we intend to send — the intent, written before the send
 * attempt (Phase 5 Module 2).
 *
 * <p>Same reasoning as {@code EmailMessage}: the row is the dedup guard, because
 * neither MSG91 nor any Indian gateway offers an idempotency key and a retry
 * after a read timeout may already have been delivered. SMS is the channel where
 * a double-send is most visible to a customer, so the claim-before-send ordering
 * matters more here, not less.
 *
 * <p>{@code dltTemplateId} is recorded for traceability against the DLT
 * registration but never required by us: whether a template must be registered
 * is a TRAI/operator rule that varies, and hard-coding it in the schema would
 * make a legal change a migration.
 */
@Entity
@Table(name = "sms_messages")
public class SmsMessage extends CreatedUpdated {

    public enum Status {
        QUEUED, SENDING, SENT, DELIVERED, FAILED, DEAD_LETTERED;

        public boolean isTerminal() {
            return this == SENT || this == DELIVERED || this == FAILED || this == DEAD_LETTERED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "thread_id", nullable = false)
    private CommunicationThread thread;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Column(name = "template_code", length = 80)
    private String templateCode;

    @Column(name = "recipient_mobile", nullable = false, length = 20)
    private String recipientMobile;

    @Column(name = "country_code", nullable = false, length = 8)
    private String countryCode = "+91";

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "dlt_template_id", length = 80)
    private String dltTemplateId;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider = "MSG91";

    @Column(name = "provider_message_id", length = 120)
    private String providerMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "failure_reason", length = 300)
    private String failureReason;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt = Instant.now();

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public void claimAttempt() {
        this.status = Status.SENDING;
        this.attempts = this.attempts + 1;
    }

    public void markSent(String providerMessageId, Instant at) {
        this.status = Status.SENT;
        this.providerMessageId = providerMessageId;
        this.sentAt = at;
        this.lastError = null;
    }

    public void markDelivered(Instant at) {
        this.status = Status.DELIVERED;
        this.deliveredAt = at;
    }

    public void markFailed(String error, String reason) {
        this.status = Status.FAILED;
        this.lastError = error;
        this.failureReason = reason;
    }

    public void releaseForRetry() {
        this.status = Status.QUEUED;
    }

    public void markDeadLettered() {
        this.status = Status.DEAD_LETTERED;
    }

    /**
     * Give up on this row, keeping the reason why.
     *
     * <p>One method rather than {@code markDeadLettered()} then
     * {@code markFailed()}: {@link #markFailed(String, String)} also sets the
     * status, so calling both in that order leaves the row {@code FAILED}, which
     * reads as "still owed an attempt" rather than "budget spent".
     */
    public void markDeadLettered(String error, String reason) {
        this.status = Status.DEAD_LETTERED;
        this.lastError = error;
        this.failureReason = reason;
    }

    public UUID getId() { return id; }
    public CommunicationThread getThread() { return thread; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public String getTemplateCode() { return templateCode; }
    public String getRecipientMobile() { return recipientMobile; }
    public String getCountryCode() { return countryCode; }
    public String getBodyText() { return bodyText; }
    public String getDltTemplateId() { return dltTemplateId; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getFailureReason() { return failureReason; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }

    public void setThread(CommunicationThread thread) { this.thread = thread; }
    public void setSubjectType(SubjectType subjectType) { this.subjectType = subjectType; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public void setRecipientMobile(String recipientMobile) { this.recipientMobile = recipientMobile; }
    public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public void setDltTemplateId(String dltTemplateId) { this.dltTemplateId = dltTemplateId; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setStatus(Status status) { this.status = status; }
}
