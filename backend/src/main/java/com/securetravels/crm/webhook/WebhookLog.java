package com.securetravels.crm.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One inbound webhook attempt (success / duplicate / failed) with the raw payload. */
@Entity
@Table(name = "webhook_logs")
public class WebhookLog {

    public enum Status { success, duplicate, failed }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(columnDefinition = "text", nullable = false)
    private String payload;

    @Column(name = "lead_id")
    private UUID leadId;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected WebhookLog() {}

    public WebhookLog(String source, String payload, UUID leadId, Status status, String errorMessage) {
        this.source = source;
        this.payload = payload;
        this.leadId = leadId;
        this.status = status.name();
        this.errorMessage = errorMessage;
    }

    public UUID getId() { return id; }
    public String getSource() { return source; }
    public String getPayload() { return payload; }
    public UUID getLeadId() { return leadId; }
    public Status getStatus() { return Status.valueOf(status); }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
}