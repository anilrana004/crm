package com.securetravels.crm.communications;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.util.PhoneUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Entry point for every outbound customer message (Module 4).
 *
 * <p>Resolves the route once, at enqueue time:
 *
 * <ul>
 *   <li>{@code INLINE} — call {@link WhatsAppSender} on this thread, with a
 *       bounded retry loop. No broker needed, so the test suite and local dev
 *       need nothing installed.</li>
 *   <li>{@code BROKER} — publish the message id to RabbitMQ and let
 *       {@link WhatsAppDispatchListener} deliver.</li>
 * </ul>
 *
 * <p><b>A failed publish falls back to inline delivery rather than dropping
 * the message.</b> The broker is degraded-tolerant by design (ADR 0005), so a
 * broker outage must not silently stop customer communication — and the
 * fallback is honest about it, because the row is still tracked in the database
 * with its own retry budget.
 *
 * <p>The queue payload is just the message UUID as a UTF-8 string. That keeps
 * messages small, human-readable in the management UI, and decoupled from any
 * Java serialization format — a schema change can never break message replay.
 */
@Service
public class WhatsAppDispatchService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppDispatchService.class);

    /** A row untouched in SENDING for this long is assumed to be a crash victim. */
    private static final long STUCK_MINUTES = 10;

    private final WhatsAppMessageRepository messages;
    private final WhatsAppTemplateRepository templates;
    private final TimelineEventRepository timeline;
    private final WhatsAppSender sender;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final ApplicationEventPublisher events;
    private final RabbitTemplate rabbit;   // null when messaging mode is INLINE

    public WhatsAppDispatchService(WhatsAppMessageRepository messages,
                                   WhatsAppTemplateRepository templates,
                                   TimelineEventRepository timeline,
                                   WhatsAppSender sender,
                                   TransactionTemplate tx,
                                   AppProperties props,
                                   ApplicationEventPublisher events,
                                   ObjectProvider<RabbitTemplate> rabbit) {
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
        this.sender = sender;
        this.tx = tx;
        this.props = props;
        this.events = events;
        // Absent in INLINE mode: no AMQP beans are declared at all, so the app
        // has no connection to a broker it does not use.
        this.rabbit = rabbit.getIfAvailable();
    }

    /**
     * Record the intent, log it to the timeline, and hand it to the route.
     *
     * <p>Delivery is triggered by {@link WhatsAppQueuedEvent}, which
     * {@link WhatsAppRoutingListener} handles {@code AFTER_COMMIT}. Routing from
     * inside this method would be a subtle data-loss bug, not a style choice: in
     * {@code BROKER} mode the consumer can receive the id and run
     * {@code claimForSending} before this transaction commits, see zero
     * affected rows, conclude the message was already handled, and ack it —
     * leaving a {@code QUEUED} row that neither the queue nor the recovery sweep
     * will ever revisit.
     *
     * <p>{@code REQUIRES_NEW} rather than the default {@code REQUIRED}, because
     * the main caller is an {@code AFTER_COMMIT} listener. At that point the
     * outer transaction is committed, but Spring's synchronisation context still
     * reports one as active, so a {@code REQUIRED} call silently joins a
     * transaction that no longer exists and the first {@code save} fails with
     * {@code TransactionRequiredException}. Queuing a message must be durable in
     * its own right regardless of what called it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WhatsAppMessage enqueue(SubjectType subjectType, UUID subjectId,
                                   String templateCode, String mobile, List<String> bodyValues) {
        String digits = PhoneUtils.normalize(mobile);
        if (digits == null) {
            throw new BadRequestException("Invalid WhatsApp recipient mobile: " + mobile);
        }
        WhatsAppTemplate template = templates.findByCodeAndEnabledTrue(templateCode)
                .orElseThrow(() -> new BadRequestException(
                        "No enabled WhatsApp template with code " + templateCode));

        List<String> values = bodyValues == null ? List.of() : List.copyOf(bodyValues);
        if (values.size() != template.getExpectedParams()) {
            // Checked here rather than at the provider, so a caller bug costs a
            // 400 instead of a plan quota and a DLQ slot.
            throw new BadRequestException("Template " + templateCode + " expects "
                    + template.getExpectedParams() + " body value(s) but " + values.size() + " were supplied");
        }

        WhatsAppMessage row = messages.save(WhatsAppMessage.queued(
                subjectType, subjectId, templateCode, digits, props.getWhatsApp().getCountryCode(), values));

        timeline.save(TimelineEvent.outboundTemplate(
                subjectType, subjectId, TimelineEvent.Kind.TEMPLATE_QUEUED, templateCode,
                "Queued " + templateCode + " to " + digits,
                props.getWhatsApp().live() ? InteraktWhatsAppGateway.PROVIDER : SandboxWhatsAppGateway.PROVIDER,
                null, digits, values));

        events.publishEvent(new WhatsAppQueuedEvent(row.getId()));
        return row;
    }

    /**
     * Publish to the broker, or deliver inline.
     *
     * <p>Always called with the enqueue transaction already committed, so the
     * consumer it wakes can actually see the row. In {@code INLINE} mode this
     * runs on the thread that committed — see {@link WhatsAppRoutingListener}
     * for what that means for request latency.
     */
    void route(UUID messageId) {
        if (props.getMessaging().brokerEnabled() && rabbit != null) {
            try {
                publish(messageId);
                return;
            } catch (AmqpException e) {
                // Do not drop: deliver inline. The row carries its own retry
                // budget, so this degrades to INLINE behaviour transparently.
                log.warn("[whatsapp] broker publish failed for {}; falling back to inline delivery: {}",
                        messageId, e.getMessage());
            }
        }
        dispatchInline(messageId);
    }

    private void publish(UUID messageId) {
        rabbit.convertAndSend(props.getMessaging().getExchange(), props.getMessaging().getRoutingKey(),
                messageId.toString(), message -> {
                    MessageProperties p = new MessageProperties();
                    // Persistent: a broker restart must not lose an owed message.
                    p.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    p.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
                    p.setContentEncoding(StandardCharsets.UTF_8.name());
                    p.setMessageId(messageId.toString());
                    return new Message(messageId.toString().getBytes(StandardCharsets.UTF_8), p);
                });
    }

    /**
     * Retry loop for the no-broker path. Backoff is linear
     * ({@code (attempt-1) * backoff}) to match the broker's stateless retry
     * advice, so switching modes does not change the user-visible cadence.
     */
    void dispatchInline(UUID messageId) {
        int maxAttempts = props.getMessaging().getMaxAttempts();
        long backoff = props.getMessaging().getRetryBackoffMillis();

        for (int i = 0; i < maxAttempts; i++) {
            WhatsAppSender.AttemptResult result = sender.attempt(messageId, maxAttempts);
            if (result.sent() || !result.retryable()) {
                return;
            }
            if (i < maxAttempts - 1) {
                sleep((i + 1) * backoff);
            }
        }
        log.warn("[whatsapp] inline dispatch for {} exhausted {} attempts", messageId, maxAttempts);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while backing off a WhatsApp send", e);
        }
    }

    /**
     * Sweep messages stranded in SENDING by a process death, then re-route them.
     *
     * <p>Without this, a crash between the claim and the provider response
     * leaves a row invisible to both the queue (already acked) and the DLQ. The
     * database work is one transaction; routing happens <em>after</em> it
     * commits, because a consumer that cannot yet see the row would treat it
     * as unclaimable and drop the delivery.
     */
    @Scheduled(fixedDelayString = "${app.messaging.recovery-sweep-millis:60000}")
    public void recoverStuckMessages() {
        Instant cutoff = Instant.now().minusSeconds(STUCK_MINUTES * 60L);
        List<UUID> stuck = tx.execute(status ->
                messages.findStuckSendingIds(cutoff, WhatsAppMessage.Status.SENDING));
        if (stuck == null || stuck.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status -> messages.recoverStuckSending(
                cutoff, WhatsAppMessage.Status.QUEUED, WhatsAppMessage.Status.SENDING));
        log.warn("[whatsapp] recovered {} message(s) stranded in SENDING; re-dispatching", stuck.size());
        stuck.forEach(this::route);
    }
}
