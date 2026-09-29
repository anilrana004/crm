package com.securetravels.crm.communications.email;

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
 * One outbound email we intend to send — the <em>intent</em>, written before
 * the send attempt (Phase 5 Module 2).
 *
 * <p>Deliberately the same shape as {@code WhatsAppMessage}, because the same
 * reasoning applies: SES offers no {@code Idempotency-Key} and a retry after a
 * read timeout may already have delivered. The row is the dedup guard, so
 * claiming it atomically before the HTTP call is what makes a redelivered queue
 * message a no-op instead of a second email to a real customer.
 *
 * <p>{@code BOUNCED} and {@code COMPLAINED} are terminal and must feed back
 * into consent (a hard bounce is not consent to keep emailing).
 */
@Entity
@Table(name = "email_messages")
public class EmailMessage extends CreatedUpdated {

    public enum Status {
        QUEUED, SENDING, SENT, DELIVERED, OPENED, BOUNCED, COMPLAINED, FAILED, DEAD_LETTERED;

        public boolean isTerminal() {
            return this == SENT || this == DELIVERED || this == OPENED
                    || this == BOUNCED || this == COMPLAINED
                    || this == FAILED || this == DEAD_LETTERED;
        }

        /** A hard bounce or spam complaint: stop emailing this address. */
        public boolean isNegativeSignal() {
            return this == BOUNCED || this == COMPLAINED;
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

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "subject_line", length = 300)
    private String subjectLine;

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "body_html")
    private String bodyHtml;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider = "SES";

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

    @Column(name = "opened_at")
    private Instant openedAt;

    @Column(name = "bounced_at")
    private Instant bouncedAt;

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

    public void markOpened(Instant at) {
        this.status = Status.OPENED;
        if (this.openedAt == null) {
            this.openedAt = at;
        }
    }

    public void markBounced(String reason, Instant at) {
        this.status = Status.BOUNCED;
        this.bouncedAt = at;
        this.failureReason = reason;
    }

    public void markComplained(String reason) {
        this.status = Status.COMPLAINED;
        this.failureReason = reason;
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
     * <p>Exists as one method rather than {@code markDeadLettered()} followed by
     * {@code markFailed()} because {@link #markFailed(String, String)} sets the
     * status too — calling both in that order silently ends the row as
     * {@code FAILED} rather than {@code DEAD_LETTERED}, and the two mean
     * different things operationally: {@code FAILED} is still owed an attempt,
     * {@code DEAD_LETTERED} is not.
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
    public String getRecipientEmail() { return recipientEmail; }
    public String getSubjectLine() { return subjectLine; }
    public String getBodyText() { return bodyText; }
    public String getBodyHtml() { return bodyHtml; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public Status getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public String getFailureReason() { return failureReason; }
    public Instant getQueuedAt() { return queuedAt; }
    public Instant getSentAt() { return sentAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getBouncedAt() { return bouncedAt; }

    public void setThread(CommunicationThread thread) { this.thread = thread; }
    public void setSubjectType(SubjectType subjectType) { this.subjectType = subjectType; }
    public void setSubjectId(UUID subjectId) { this.subjectId = subjectId; }
    public void setTemplateCode(String templateCode) { this.templateCode = templateCode; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }
    public void setSubjectLine(String subjectLine) { this.subjectLine = subjectLine; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }
    public void setProvider(String provider) { this.provider = provider; }
    public void setStatus(Status status) { this.status = status; }
}
