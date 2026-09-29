package com.securetravels.crm.communications.sms;

import java.util.UUID;

/**
 * Raised after an SMS intent row commits (Phase 5 Module 2).
 *
 * <p>Same contract as {@code WhatsAppQueuedEvent} / {@code EmailQueuedEvent}: the
 * row must be durable before any delivery attempt, or a consumer that runs early
 * acks a message the database has never heard of.
 */
public record SmsQueuedEvent(UUID messageId) {
}
