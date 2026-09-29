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
 * One line in a customer's consent history (Phase 5 Module 1).
 *
 * <p>Append-only by contract: the effective status is the row with the latest
 * {@code occurredAt} for a (customer, channel, purpose), and a new instruction
 * is always a new row. Updating in place would erase the timeline that
 * regulators and audits expect.
 */
@Entity
@Table(name = "consent_records")
public class ConsentRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TimelineEvent.Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Purpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ConsentSource source;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "evidence_ref", length = 255)
    private String evidenceRef;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(nullable = false)
    private long version;

    protected ConsentRecord() {
    }

    public ConsentRecord(UUID customerId, TimelineEvent.Channel channel, Purpose purpose,
                         ConsentStatus status, ConsentSource source, Instant occurredAt,
                         String evidenceRef, UUID createdBy) {
        this.customerId = customerId;
        this.channel = channel;
        this.purpose = purpose;
        this.status = status;
        this.source = source;
        this.occurredAt = occurredAt;
        this.evidenceRef = evidenceRef;
        this.createdBy = createdBy;
        this.version = 0;
    }

    public UUID getId() { return id; }
    public UUID getCustomerId() { return customerId; }
    public TimelineEvent.Channel getChannel() { return channel; }
    public Purpose getPurpose() { return purpose; }
    public ConsentStatus getStatus() { return status; }
    public ConsentSource getSource() { return source; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getEvidenceRef() { return evidenceRef; }
    public UUID getCreatedBy() { return createdBy; }
}