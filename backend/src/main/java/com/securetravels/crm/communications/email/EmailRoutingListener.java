package com.securetravels.crm.communications.email;

import com.securetravels.crm.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Delivers a committed email intent (Phase 5 Module 2).
 *
 * <p>{@code AFTER_COMMIT} is the whole point: the row must be visible to this
 * thread (and to any broker consumer) before the provider is called. The
 * bounded inline retry loop matches the WhatsApp path so the cadence a customer
 * experiences does not change with messaging mode.
 */
@Component
public class EmailRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(EmailRoutingListener.class);

    private final EmailDispatchService dispatch;
    private final AppProperties props;

    public EmailRoutingListener(EmailDispatchService dispatch, AppProperties props) {
        this.dispatch = dispatch;
        this.props = props;
    }

    /**
     * Pacing between retries on the no-broker path, from configuration.
     *
     * <p>The budget itself is deliberately <em>not</em> read here: it belongs to
     * {@code EmailDispatchService}, where the attempt counter is durable, so
     * switching to a real broker cannot silently change how many times a
     * customer is emailed. This is only the pause, which is a local concern —
     * a test suite should not sleep for it, and an operator under load may want
     * it longer. (It was previously a hard-coded 500ms, which meant
     * {@code app.messaging.retry-backoff-millis} was a key that did nothing.)
     */
    private long backoffMillis() {
        return props.getMessaging().getRetryBackoffMillis();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(EmailQueuedEvent event) {
        deliver(event.messageId());
    }

    /**
     * The same method the scheduled recovery sweep calls for a row stranded in
     * {@code SENDING} by a crash.
     *
     * <p>Attempts once, then re-checks. A retryable failure puts the row back to
     * {@code QUEUED}, so the loop continues; a permanent rejection or an
     * exhausted budget leaves it in a terminal state and the loop stops
     * immediately rather than burning attempts on an address SES already
     * refused.
     */
    @EventListener
    public void deliver(java.util.UUID messageId) {
        int maxAttempts = props.getMessaging().getMaxAttempts();
        for (int i = 0; i < maxAttempts; i++) {
            dispatch.attempt(messageId);
            if (!dispatch.isStillQueued(messageId)) {
                return;
            }
            if (i >= maxAttempts - 1) {
                break;   // budget spent; no point sleeping before giving up
            }
            try {
                // Back off *after* a failed attempt, never before the first one:
                // a healthy send should not wait half a second for nothing.
                Thread.sleep((i + 1) * backoffMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("[email] inline delivery for {} exhausted {} attempts", messageId, maxAttempts);
    }
}
