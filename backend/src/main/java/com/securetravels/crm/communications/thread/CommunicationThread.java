package com.securetravels.crm.communications.thread;

import com.securetravels.crm.common.audit.CreatedUpdated;
import com.securetravels.crm.communications.SubjectType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One customer conversation on one channel — the row the unified inbox lists
 * (Phase 5 Module 2).
 *
 * <p>A thread is the only <em>mutable</em> object in the communications model.
 * Messages are append-only facts; the thread is the working queue on top of
 * them (assignee, unread badge, open/closed). Keeping the two separate is what
 * lets the message log stay an audit trail: we never rewrite history to keep
 * the inbox current.
 *
 * <p>Uniqueness is {@code (subject_type, subject_id, channel)} — one inbox
 * conversation per person per channel, not per message. A webhook redelivery
 * resolves the same thread instead of opening a second one.
 *
 * <p>The 24h service window is tracked here rather than recomputed per send.
 * WhatsApp only allows business-initiated templates inside a customer service
 * window, and storing {@code window_expires_at} lets both the inbox and the
 * send gate answer "may we send a template?" from one stored fact.
 */
@Entity
@Table(name = "communication_threads")
public class CommunicationThread extends CreatedUpdated {

    /** WhatsApp's customer service window: 24h from the last inbound message. */
    public static final Duration SERVICE_WINDOW = Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    private UUID subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommunicationChannel channel;

    @Column(name = "customer_mobile", length = 20)
    private String customerMobile;

    @Column(name = "customer_name", length = 200)
    private String customerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ThreadStatus status = ThreadStatus.OPEN;

    @Column(name = "unread_count", nullable = false)
    private int unreadCount = 0;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "last_message_at", nullable = false)
    private Instant lastMessageAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "last_direction", nullable = false, length = 10)
    private Direction lastDirection = Direction.INBOUND;

    @Column(name = "last_preview", length = 300)
    private String lastPreview;

    @Column(name = "window_opened_at")
    private Instant windowOpenedAt;

    @Column(name = "window_expires_at")
    private Instant windowExpiresAt;

    /** Which side spoke last. */
    public enum Direction { INBOUND, OUTBOUND }

    public static CommunicationThread open(SubjectType subjectType, UUID subjectId,
                                           CommunicationChannel channel, String mobile, String name) {
        CommunicationThread t = new CommunicationThread();
        t.subjectType = subjectType;
        t.subjectId = subjectId;
        t.channel = channel;
        t.customerMobile = mobile;
        t.customerName = name;
        return t;
    }

    /**
     * Record an inbound message: bumps the badge, moves the preview, and
     * re-opens the 24h service window (an inbound message is what opens it).
     */
    public void recordInbound(String preview, Instant at) {
        lastDirection = Direction.INBOUND;
        lastMessageAt = at;
        lastPreview = preview;
        unreadCount = unreadCount + 1;
        windowOpenedAt = at;
        windowExpiresAt = at.plus(SERVICE_WINDOW);
    }

    /**
     * Record an outbound message. An outbound does <em>not</em> open or extend
     * the service window — only the customer's own message does.
     */
    public void recordOutbound(String preview, Instant at) {
        lastDirection = Direction.OUTBOUND;
        lastMessageAt = at;
        lastPreview = preview;
    }

    public void markRead() {
        unreadCount = 0;
    }

    public void assign(UUID userId) {
        this.assignedTo = userId;
    }

    public void changeStatus(ThreadStatus status) {
        this.status = status;
    }

    public boolean isServiceWindowOpen(Instant now) {
        return windowExpiresAt != null && now.isBefore(windowExpiresAt);
    }

    public UUID getId() { return id; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public CommunicationChannel getChannel() { return channel; }
    public String getCustomerMobile() { return customerMobile; }
    public String getCustomerName() { return customerName; }
    public ThreadStatus getStatus() { return status; }
    public int getUnreadCount() { return unreadCount; }
    public UUID getAssignedTo() { return assignedTo; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public Direction getLastDirection() { return lastDirection; }
    public String getLastPreview() { return lastPreview; }
    public Instant getWindowOpenedAt() { return windowOpenedAt; }
    public Instant getWindowExpiresAt() { return windowExpiresAt; }
}
