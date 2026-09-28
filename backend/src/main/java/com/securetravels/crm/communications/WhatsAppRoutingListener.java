package com.securetravels.crm.communications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Routes a committed message for delivery (Module 4).
 *
 * <p>{@code AFTER_COMMIT} is the load-bearing detail. Routing inside the
 * enqueue transaction lets a RabbitMQ consumer run {@code claimForSending}
 * against a row that is not committed yet, get zero rows back, and ack the
 * delivery as "already handled" — silently stranding the message. Committing
 * first makes the ordering "the row exists" then "someone delivers it" a
 * property of the system rather than a timing accident.
 *
 * <p>{@code fallbackExecution = true} covers the case where no transaction was
 * ever active (a proxied self-invocation, or a future caller that bypasses
 * {@code @Transactional}). Without it that would drop the delivery on the floor;
 * with it, delivery simply happens synchronously on the calling thread — slower,
 * but never silently lost.
 *
 * <p><b>Latency:</b> in {@code INLINE} mode the send runs on the thread that
 * committed, so an operator-initiated send returns once delivery has actually
 * been attempted (bounded by the inline retry budget). That is intentional
 * feedback for a rare, human-initiated action, and it happens strictly after
 * the business transaction has committed, so no pooled connection is held across
 * the provider call. {@code BROKER} mode is fully asynchronous.
 */
@Component
public class WhatsAppRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppRoutingListener.class);

    private final WhatsAppDispatchService dispatch;

    public WhatsAppRoutingListener(WhatsAppDispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onQueued(WhatsAppQueuedEvent event) {
        try {
            dispatch.route(event.messageId());
        } catch (RuntimeException e) {
            // The row is committed and durable. Losing it now would be silent
            // data loss, so shout instead. The recovery sweep will pick it up if
            // it is stuck in SENDING.
            log.error("[whatsapp] could not route committed message {}", event.messageId(), e);
        }
    }
}
