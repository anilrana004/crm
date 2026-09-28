package com.securetravels.crm.communications;

import com.securetravels.crm.common.exception.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A <em>single</em> send attempt (Module 4).
 *
 * <p>The transaction split is the whole point, and it is not stylistic:
 *
 * <pre>
 *   tx1   claim    QUEUED -&gt; SENDING   (commits)
 *   ---   HTTP     no DB connection held while waiting on the provider
 *   tx2   record   SENT | QUEUED(retry) | FAILED | DEAD_LETTERED
 * </pre>
 *
 * <p>If the claim were still uncommitted during the HTTP call, two consumers
 * would both observe {@code QUEUED} and both send — defeating the single-writer
 * guard exactly when it matters. Holding one transaction across the HTTP call
 * would instead pin a pooled connection for the whole read timeout.
 *
 * <p>Boundaries are expressed with {@link TransactionTemplate} rather than
 * {@code @Transactional} on split private/public methods: self-invocation
 * bypasses the transactional proxy, so annotated methods in a single class
 * would silently run with <em>no</em> transaction — the exact opposite of the
 * intent.
 *
 * <p>This class performs no retry loop. In {@code BROKER} mode the container's
 * retry advice owns retries; in {@code INLINE} mode
 * {@link WhatsAppDispatchService} does. One policy per mode, not two
 * competing ones.
 */
@Service
public class WhatsAppSender {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppSender.class);

    private final WhatsAppMessageRepository messages;
    private final WhatsAppTemplateRepository templates;
    private final TimelineEventRepository timeline;
    private final WhatsAppGateway gateway;
    private final TransactionTemplate tx;

    public WhatsAppSender(WhatsAppMessageRepository messages, WhatsAppTemplateRepository templates,
                          TimelineEventRepository timeline, WhatsAppGateway gateway,
                          TransactionTemplate tx) {
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
        this.gateway = gateway;
        // REQUIRES_NEW, always. The default REQUIRED would let the claim and the
        // outcome each silently join whatever transaction happens to be ambient,
        // which defeats the entire point of splitting them — and the main caller
        // (WhatsAppRoutingListener) runs AFTER_COMMIT, where Spring still reports
        // a transaction as active even though it has completed. Joining that
        // phantom transaction makes the first write fail with
        // "no transaction is in progress". A private template over the same
        // transaction manager also leaves the shared bean untouched.
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Outcome of one attempt, for the caller's retry decision. */
    public record AttemptResult(boolean sent, boolean retryable, String error) {
        static AttemptResult notSent() { return new AttemptResult(false, false, null); }
    }

    /**
     * Claim the row, call the provider, record the outcome.
     *
     * <p>Never throws for a provider fault: a rejection is data, not an
     * exception, so the caller decides what to do with it. The only exceptions
     * that escape are genuine bugs.
     *
     * <p><b>Who owns the retry policy is a parameter, not a second code path.</b>
     * In {@code INLINE} mode there is no broker, so this class enforces the
     * budget itself and marks the row {@code DEAD_LETTERED} when it runs out.
     * In {@code BROKER} mode the container's retry advice is the policy and the
     * dead-letter queue is the artifact, so the caller passes
     * {@link Integer#MAX_VALUE} and this class never declares exhaustion — two
     * counters racing over the same row is how a message ends up both
     * dead-lettered and retried.
     *
     * @return {@code sent=true} only if the provider accepted it.
     */
    public AttemptResult attempt(UUID messageId, int retryBudget) {
        Claimed claimed = tx.execute(status -> claim(messageId));
        if (claimed == null) {
            // Already terminal, or another consumer holds the claim. Nothing sent.
            log.debug("[whatsapp] message {} not claimable", messageId);
            return AttemptResult.notSent();
        }

        WhatsAppMessage row = claimed.row();
        Optional<WhatsAppTemplate> template = templates.findByCodeAndEnabledTrue(row.getTemplateCode());
        if (template.isEmpty()) {
            String error = "No enabled WhatsApp template with code " + row.getTemplateCode();
            // A disabled/missing template is a configuration fault: permanent,
            // and it must not consume retry budget.
            recordPermanent(claimed, error);
            return new AttemptResult(false, false, error);
        }

        WhatsAppGateway.SendResult result;
        try {
            result = gateway.send(new WhatsAppGateway.SendCommand(
                    row.getCountryCode(), row.getRecipientMobile(),
                    template.get().getInteraktName(), template.get().getLanguageCode(),
                    row.bodyValues(), row.getCallbackData()));
        } catch (RuntimeException e) {
            // The gateway contract says it converts provider faults into results.
            // A throw here is a bug, but treating it as retryable is still safer
            // than losing the message.
            log.error("[whatsapp] gateway threw unexpectedly for message {}", messageId, e);
            result = WhatsAppGateway.SendResult.failure(
                    "gateway error: " + e.getMessage(), null, null, true);
        }

        if (result.success()) {
            recordSuccess(claimed, result.providerMessageId());
            return new AttemptResult(true, false, null);
        }

        boolean canRetry = result.retryable() && claimed.attempts() < retryBudget;
        if (canRetry) {
            // Leave the row QUEUED for the inline loop or the container's retry
            // advice. No sleep here: backoff belongs to the retry policy.
            markRetry(claimed, result.error());
            return new AttemptResult(false, true, result.error());
        }
        // Either a permanent rejection, or the budget is spent. In the latter
        // case DEAD_LETTERED is the record that a human needs to see.
        recordTerminal(claimed, result, !canRetry && result.retryable());
        return new AttemptResult(false, false, result.error());
    }

    // ---- tx1: claim ----

    private Claimed claim(UUID messageId) {
        // The status flip and the attempt counter are one statement, so they
        // can never drift apart.
        int claimed = messages.claimForSending(messageId,
                WhatsAppMessage.Status.QUEUED, WhatsAppMessage.Status.SENDING);
        if (claimed == 0) {
            return null;
        }
        return messages.findById(messageId)
                .map(row -> new Claimed(row, row.getAttempts()))
                .orElse(null);
    }

    // ---- tx2: outcomes ----

    private void recordSuccess(Claimed claimed, String providerMessageId) {
        tx.executeWithoutResult(status -> {
            WhatsAppMessage row = reload(claimed);
            row.markSent(providerMessageId, Instant.now());
            messages.save(row);
            timeline.save(TimelineEvent.outboundTemplate(
                    row.getSubjectType(), row.getSubjectId(), TimelineEvent.Kind.TEMPLATE_SENT,
                    row.getTemplateCode(),
                    "Sent " + row.getTemplateCode() + " to " + row.getRecipientMobile(),
                    gateway.provider(), providerMessageId, row.getRecipientMobile(), row.bodyValues()));
        });
    }

    private void markRetry(Claimed claimed, String error) {
        tx.executeWithoutResult(status -> {
            WhatsAppMessage row = reload(claimed);
            row.markRetry(error);
            messages.save(row);
        });
    }

    private void recordTerminal(Claimed claimed, WhatsAppGateway.SendResult result, boolean exhausted) {
        tx.executeWithoutResult(status -> {
            WhatsAppMessage row = reload(claimed);
            if (exhausted) {
                row.markDeadLettered(result.error());
            } else {
                row.markFailed(result.error(), result.channelErrorCode(), result.failureReason());
            }
            messages.save(row);
            timeline.save(TimelineEvent.outboundTemplate(
                    row.getSubjectType(), row.getSubjectId(), TimelineEvent.Kind.TEMPLATE_FAILED,
                    row.getTemplateCode(),
                    "Failed " + row.getTemplateCode() + " to " + row.getRecipientMobile()
                            + " after " + claimed.attempts() + " attempt(s): " + result.error(),
                    gateway.provider(), null, row.getRecipientMobile(), row.bodyValues()));
            log.warn("[whatsapp] message {} {}: {}", row.getId(),
                    exhausted ? "dead-lettered" : "failed permanently", result.error());
        });
    }

    private void recordPermanent(Claimed claimed, String error) {
        tx.executeWithoutResult(status -> {
            WhatsAppMessage row = reload(claimed);
            row.markFailed(error, null, null);
            messages.save(row);
            timeline.save(TimelineEvent.outboundTemplate(
                    row.getSubjectType(), row.getSubjectId(), TimelineEvent.Kind.TEMPLATE_FAILED,
                    row.getTemplateCode(),
                    "Failed " + row.getTemplateCode() + " to " + row.getRecipientMobile() + ": " + error,
                    gateway.provider(), null, row.getRecipientMobile(), row.bodyValues()));
            log.warn("[whatsapp] message {} failed permanently: {}", row.getId(), error);
        });
    }

    /** Re-read after the claim so the in-memory row carries the new attempt count. */
    private WhatsAppMessage reload(Claimed claimed) {
        return messages.findById(claimed.row().getId())
                .orElseThrow(() -> new BadRequestException(
                        "WhatsApp message vanished mid-send: " + claimed.row().getId()));
    }

    private record Claimed(WhatsAppMessage row, int attempts) {
    }
}
