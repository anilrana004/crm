package com.securetravels.crm.communications.email;

import java.util.UUID;

/**
 * Raised after an email intent row commits (Phase 5 Module 2).
 *
 * <p>Same contract as {@code WhatsAppQueuedEvent}: the row is durable before
 * anything tries to deliver it. A consumer that ran inside the enqueue
 * transaction could not see the row, would treat it as unclaimable, and would
 * ack it — losing the message with nothing left for the recovery sweep to find.
 */
public record EmailQueuedEvent(UUID messageId) {
}
