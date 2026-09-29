package com.securetravels.crm.communications.sms;

import com.securetravels.crm.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Delivers a committed SMS intent (Phase 5 Module 2), with the same bounded
 * inline retry loop as the other two channels.
 */
@Component
public class SmsRoutingListener {

    private static final Logger log = LoggerFactory.getLogger(SmsRoutingListener.class);

    private final SmsDispatchService dispatch;
    private final AppProperties props;

    public SmsRoutingListener(SmsDispatchService dispatch, AppProperties props) {
        this.dispatch = dispatch;
        this.props = props;
    }

    /**
     * The inline pacing, from configuration rather than a constant.
     *
     * <p>The <em>budget</em> of how many attempts a row is owed is deliberately
     * not read here: it belongs to {@code SmsDispatchService}, where the attempt
     * counter is durable, so that swapping in a broker cannot silently change
     * how many times a customer is texted. This is only the pause between
     * attempts, which is a local concern and genuinely tunable — a test suite
     * should not sleep for it, and an operator under load may want it longer.
     */
    private int maxAttempts() {
        return props.getMessaging().getMaxAttempts();
    }

    private long backoffMillis() {
        return props.getMessaging().getRetryBackoffMillis();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onQueued(SmsQueuedEvent event) {
        deliver(event.messageId());
    }

    public void deliver(UUID messageId) {
        int maxAttempts = maxAttempts();
        for (int i = 0; i < maxAttempts; i++) {
            dispatch.attempt(messageId);
            // Stop the moment the row is no longer owed an attempt. A permanent
            // rejection is terminal on the first try, so burning the rest of the
            // budget on it would only pay to be refused again.
            if (!dispatch.isStillQueued(messageId)) {
                return;
            }
            if (i < maxAttempts - 1) {
                try {
                    Thread.sleep((i + 1) * backoffMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        log.warn("[sms] inline delivery for {} exhausted {} attempts", messageId, maxAttempts);
    }
}
