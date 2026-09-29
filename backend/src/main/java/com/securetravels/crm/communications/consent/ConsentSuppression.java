package com.securetravels.crm.communications.consent;

import com.securetravels.crm.communications.TimelineEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An opt-out recorded against a phone number that has no {@code customer360}
 * row yet (a "STOP" before we ever talk to them, or a number that never became
 * a customer). Unique per (number, channel) — repeating the instruction is not
 * new information.
 *
 * <p>Once a customer exists for the number, the latest {@code consent_records}
 * row wins over this line: an explicit re-grant is a newer instruction than the
 * stale STOP.
 */
@Entity
@Table(name = "consent_suppressions")
public class ConsentSuppression {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "mobile_digits", length = 20)
    private String mobileDigits;

    /**
     * Email suppressions (a hard bounce, a spam complaint) are keyed by address:
     * they arrive with no phone number at all, and inventing one would make the
     * row a lie. Exactly one of the two identifiers is set — see the
     * {@code ck_suppression_identity} constraint added in V15.
     */
    @Column(name = "email_address", length = 255)
    private String emailAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TimelineEvent.Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsentSource source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "evidence_ref", length = 255)
    private String evidenceRef;

    protected ConsentSuppression() {
    }

    /**
     * One canonical constructor. Both public entry points funnel through it, so
     * the "exactly one identifier" rule cannot be bypassed by adding a third
     * factory later.
     */
    private ConsentSuppression(String mobileDigits, String emailAddress, TimelineEvent.Channel channel,
                               ConsentSource source, Instant occurredAt, String evidenceRef) {
        this.mobileDigits = mobileDigits;
        this.emailAddress = emailAddress;
        this.channel = channel;
        this.source = source;
        this.occurredAt = occurredAt;
        this.evidenceRef = evidenceRef;
    }

    public static ConsentSuppression forMobile(String mobileDigits, TimelineEvent.Channel channel,
                                                ConsentSource source, Instant occurredAt,
                                                String evidenceRef) {
        return new ConsentSuppression(mobileDigits, null, channel, source, occurredAt, evidenceRef);
    }

    public static ConsentSuppression forEmail(String emailAddress, ConsentSource source,
                                              Instant occurredAt, String evidenceRef) {
        return new ConsentSuppression(null, emailAddress, TimelineEvent.Channel.EMAIL, source,
                occurredAt, evidenceRef);
    }

    public UUID getId() { return id; }
    public String getMobileDigits() { return mobileDigits; }
    public String getEmailAddress() { return emailAddress; }
    public TimelineEvent.Channel getChannel() { return channel; }
    public ConsentSource getSource() { return source; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getEvidenceRef() { return evidenceRef; }
}