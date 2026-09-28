package com.securetravels.crm.communications;

import java.util.UUID;

/**
 * A message row has been committed and is now owed to the channel (Module 4).
 *
 * <p>Published from inside the enqueue transaction and consumed
 * {@link org.springframework.transaction.event.TransactionPhase#AFTER_COMMIT}
 * by {@link WhatsAppRoutingListener}, so that "the row exists" and "someone
 * delivers the row" cannot be reordered.
 */
public record WhatsAppQueuedEvent(UUID messageId) {
}
