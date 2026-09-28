package com.securetravels.crm.communications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The single consumer that delivers queued WhatsApp messages (Module 4).
 *
 * <p>Payload is the message UUID as text. Keeping the body to an opaque id
 * means a schema change to {@code whatsapp_messages} can never invalidate a
 * message sitting in the queue, and an operator reading the queue can go
 * straight to the row.
 *
 * <p>The listener performs exactly one attempt and delegates the retry policy
 * to the container (see {@link CommunicationMessagingConfig}). It rethrows on
 * a retryable failure so the retry advice runs, and swallows a permanent
 * rejection because retrying it would only ever fail again — that row is
 * already recorded as {@code FAILED} with its reason.
 */
@Component
@ConditionalOnProperty(prefix = "app.messaging", name = "mode", havingValue = "BROKER")
public class WhatsAppDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppDispatchListener.class);

    private final WhatsAppSender sender;

    public WhatsAppDispatchListener(WhatsAppSender sender) {
        this.sender = sender;
    }

    /**
     * The queue name comes from a property placeholder rather than a SpEL bean
     * reference ({@code #{@appProperties...}}). A {@code @ConfigurationProperties}
     * bean has no contractual name, so binding the listener's queue to one breaks
     * the whole application context the moment the properties class is registered
     * differently — and in {@code BROKER} mode that failure happens at startup.
     */
    @RabbitListener(queues = "${app.messaging.queue}",
            containerFactory = "whatsappListenerFactory")
    public void onQueuedMessage(String messageId) {
        UUID id;
        try {
            id = UUID.fromString(messageId);
        } catch (IllegalArgumentException e) {
            // Unparseable payload: retrying cannot help, and requeueing it would
            // loop forever. Rethrowing still dead-letters it, so the operator can
            // see and discard it.
            throw new IllegalArgumentException("Non-UUID WhatsApp dispatch payload: " + messageId, e);
        }

        // Integer.MAX_VALUE: the container owns the retry budget in BROKER mode,
        // so this never declares exhaustion on its own.
        WhatsAppSender.AttemptResult result = sender.attempt(id, Integer.MAX_VALUE);

        if (result.sent()) {
            log.info("[whatsapp] delivered {}", id);
            return;
        }
        if (result.retryable()) {
            throw new WhatsAppTransientException(id, result.error());
        }
        log.warn("[whatsapp] message {} permanently failed: {}", id, result.error());
    }

    /** Signals a retryable provider fault; the container decides what happens next. */
    static class WhatsAppTransientException extends RuntimeException {
        WhatsAppTransientException(UUID id, String cause) {
            super("retryable WhatsApp send failure for " + id + ": " + cause, null, false, false);
        }
    }
}
