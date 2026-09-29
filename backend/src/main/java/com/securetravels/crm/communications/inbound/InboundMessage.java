package com.securetravels.crm.communications.inbound;

import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.thread.CommunicationChannel;
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
 * One message a customer sent us (Phase 5 Module 2).
 *
 * <p>This table exists for two reasons that both matter:
 *
 * <ol>
 *   <li>It is the inbox's read side. Without it the unified inbox can show a
 *       conversation's last line but never what was actually said.</li>
 *   <li>It is the <b>idempotency guard</b>, via the unique index on
 *       {@code (provider, provider_message_id)}. Every Indian gateway and every
 *       email provider retries on a non-2xx, and a retried delivery must not
 *       produce a second row — which, for a stranger's first message, would also
 *       mean a second lead.</li>
 * </ol>
 *
 * <p>Deliberately does <em>not</em> extend {@code CreatedUpdated}: what a
 * customer said is a fact, and a fact has no {@code updated_at}. The only field
 * that may change after insertion is the resolution link, and that is an
 * arrival-time decision recorded once.
 *
 * <p>{@code subjectType}/{@code subjectId} may be null: at arrival we may have
 * no idea who is writing. Resolution is attempted immediately and the row is
 * linked when it succeeds, rather than pretending we knew all along.
 */
@Entity
@Table(name = "inbound_messages")
public class InboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private CommunicationChannel channel;

    @Column(name = "provider", nullable = false, length = 30)
    private String provider;

    @Column(name = "provider_message_id", nullable = false, length = 120)
    private String providerMessageId;

    @ManyToOne
    @JoinColumn(name = "thread_id")
    private CommunicationThread thread;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", length = 20)
    private SubjectType subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(name = "from_mobile", length = 20)
    private String fromMobile;

    @Column(name = "from_email", length = 255)
    private String fromEmail;

    @Column(name = "body")
    private String body;

    @Column(name = "is_media", nullable = false)
    private boolean media = false;

    @Column(name = "media_type", length = 60)
    private String mediaType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    public static InboundMessage of(CommunicationChannel channel, String provider, String providerMessageId,
                                    String fromMobile, String fromEmail, String body, Instant receivedAt) {
        InboundMessage m = new InboundMessage();
        m.channel = channel;
        m.provider = provider;
        m.providerMessageId = providerMessageId;
        m.fromMobile = fromMobile;
        m.fromEmail = fromEmail;
        m.body = body;
        m.receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        return m;
    }

    public void markMedia(String mediaType) {
        this.media = true;
        this.mediaType = mediaType;
    }

    /** Attach the message to the conversation and person we resolved on arrival. */
    public void attach(CommunicationThread thread, SubjectType subjectType, UUID subjectId, UUID leadId) {
        this.thread = thread;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.leadId = leadId;
    }

    public UUID getId() { return id; }
    public CommunicationChannel getChannel() { return channel; }
    public String getProvider() { return provider; }
    public String getProviderMessageId() { return providerMessageId; }
    public CommunicationThread getThread() { return thread; }
    public SubjectType getSubjectType() { return subjectType; }
    public UUID getSubjectId() { return subjectId; }
    public UUID getLeadId() { return leadId; }
    public String getFromMobile() { return fromMobile; }
    public String getFromEmail() { return fromEmail; }
    public String getBody() { return body; }
    public boolean isMedia() { return media; }
    public String getMediaType() { return mediaType; }
    public Instant getReceivedAt() { return receivedAt; }
}
