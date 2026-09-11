package com.securetravels.crm.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * One notification row per recipient per event. IN_APP rows drive the
 * sidebar bell; EMAIL rows are produced by the email stub when SMTP is
 * configured (Module 2 keeps inbox + email contract, delivery deferred).
 */
@Entity
@Table(name = "notifications", indexes = {
        @Index(name = "idx_notifications_user", columnList = "user_id, is_read, created_at")
})
public class Notification {

    public enum Channel { IN_APP, EMAIL }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    @Column(length = 300)
    private String link;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "read_at")
    private Instant readAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Notification() {}

    public Notification(UUID userId, Channel channel, String title, String body, String link) {
        this.userId = userId;
        this.channel = channel;
        this.title = title;
        this.body = body;
        this.link = link;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public Channel getChannel() { return channel; }
    public String getTitle() { return title; }
    public String getBody() { return body; }
    public String getLink() { return link; }
    public boolean isRead() { return read; }
    public Instant getReadAt() { return readAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void markRead(Instant at) {
        this.read = true;
        this.readAt = at;
    }
}