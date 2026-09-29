package com.securetravels.crm.communications.email;

import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.communications.OutboundChannelDispatcher;
import com.securetravels.crm.communications.SendRequest;
import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.communications.TimelineEventRepository;
import com.securetravels.crm.communications.consent.Purpose;
import com.securetravels.crm.communications.template.ChannelTemplate;
import com.securetravels.crm.communications.template.ChannelTemplateService;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.communications.thread.CommunicationThreadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.TransactionDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Email's channel adapter: the gate's entry point for outbound email
 * (Phase 5 Module 2).
 *
 * <p>Mirrors {@code WhatsAppDispatchService} — the intent row is written first,
 * the provider call happens after commit, and the retry/dead-letter budget lives
 * here rather than in the gateway. The differences are email's: no phone
 * normalisation, a recipient address instead of a mobile, and hard bounces
 * having to be fed back into consent.
 *
 * <p>Delivery is triggered by {@link EmailQueuedEvent} after commit for the same
 * reason as WhatsApp — see that class's dispatcher for the full argument. A
 * consumer that ran before the commit would see zero rows, conclude the message
 * was handled, and ack it.
 */
@Service
public class EmailDispatchService implements OutboundChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatchService.class);

    /**
     * Total attempts a row is owed before it is dead-lettered.
     *
     * <p>Deliberately not the same number as the routing listener's loop: the
     * listener's loop only exists to pace attempts, and the budget is enforced
     * here where the attempt count is durable, so switching to a real broker
     * does not silently change how many times a customer is emailed.
     */
    static final int MAX_ATTEMPTS = 3;

    private final EmailMessageRepository messages;
    private final ChannelTemplateService templates;
    private final TimelineEventRepository timeline;
    private final CommunicationThreadService threads;
    private final EmailGateway gateway;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;

    public EmailDispatchService(EmailMessageRepository messages, ChannelTemplateService templates,
                                TimelineEventRepository timeline, CommunicationThreadService threads,
                                EmailGateway gateway, TransactionTemplate tx,
                                ApplicationEventPublisher events) {
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
        this.threads = threads;
        this.gateway = gateway;
        // REQUIRES_NEW, always -- the same reason WhatsAppSender does it. The
        // default REQUIRED would let the claim and the outcome each silently
        // join whatever transaction happens to be ambient, which defeats the
        // point of splitting them. And the main caller is EmailRoutingListener,
        // which runs AFTER_COMMIT, where Spring still reports a transaction as
        // active even though it has completed: joining that phantom transaction
        // makes the outcome write vanish, leaving every email stuck at QUEUED
        // even though the provider was called and accepted it. A private
        // template over the same manager also leaves the shared bean untouched.
        this.tx = new TransactionTemplate(tx.getTransactionManager());
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.events = events;
    }

    @Override
    public TimelineEvent.Channel channel() {
        return TimelineEvent.Channel.EMAIL;
    }

    @Override
    public Purpose resolvePurpose(SendRequest request) {
        if (request.templateCode() == null) {
            return request.purpose() == null ? Purpose.TRANSACTIONAL : request.purpose();
        }
        return templates.purposeOf(CommunicationChannel.EMAIL, request.templateCode());
    }

    /**
     * Record the intent, log it, and wake the sender. {@code REQUIRES_NEW} on the
     * <em>public</em> method is what actually opens the transaction; anything
     * annotated further down would be defeated by self-invocation.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID dispatch(SendRequest request) {
        return enqueue(request.subjectType(), request.subjectId(), request.templateCode(),
                request.email(), request.bodyValues(), request.actorId()).getId();
    }

    EmailMessage enqueue(SubjectType subjectType, UUID subjectId, String templateCode,
                         String recipient, List<String> bodyValues, UUID actorId) {
        String email = normalizeEmail(recipient);
        List<String> values = bodyValues == null ? List.of() : List.copyOf(bodyValues);

        String subjectLine = null;
        String bodyText;
        if (templateCode != null) {
            ChannelTemplate template = templates.require(CommunicationChannel.EMAIL, templateCode);
            if (template.getApprovalStatus() != ChannelTemplate.ApprovalStatus.APPROVED) {
                throw new BadRequestException("Email template " + templateCode
                        + " is not approved (status " + template.getApprovalStatus() + ")");
            }
            if (values.size() != template.getExpectedParams()) {
                throw new BadRequestException("Template " + templateCode + " expects "
                        + template.getExpectedParams() + " body value(s) but " + values.size() + " were supplied");
            }
            subjectLine = render(template.getSubjectLine(), values);
            bodyText = render(template.getBody(), values);
        } else {
            bodyText = values.isEmpty() ? null : String.join("\n", values);
        }
        if (bodyText == null || bodyText.isBlank()) {
            throw new BadRequestException("Refusing to send an email with an empty body");
        }

        var thread = threads.resolve(subjectType, subjectId, CommunicationChannel.EMAIL, null, null);

        EmailMessage row = new EmailMessage();
        row.setThread(thread);
        row.setSubjectType(subjectType);
        row.setSubjectId(subjectId);
        row.setTemplateCode(templateCode);
        row.setRecipientEmail(email);
        row.setSubjectLine(subjectLine);
        row.setBodyText(bodyText);
        row.setProvider(gateway.provider());
        EmailMessage saved = messages.save(row);

        timeline.save(TimelineEvent.outboundTemplate(subjectType, subjectId,
                TimelineEvent.Kind.TEMPLATE_QUEUED, templateCode,
                "Queued email to " + email, gateway.provider(), null, null, values));

        // Wake the sender only after this transaction commits. Publishing from
        // inside it would hand the listener a message id whose row is not yet
        // visible to any other connection, so the attempt would find nothing,
        // conclude the email was handled, and never send it.
        events.publishEvent(new EmailQueuedEvent(saved.getId()));

        return saved;
    }

    /**
     * One send attempt for a row that is still {@code QUEUED}.
     *
     * <p>Deliberately not {@code @Transactional}. The provider call is a network
     * round trip to SES that can take seconds or hang, and holding a pooled
     * connection and an open transaction across it would exhaust the pool under
     * any burst of sends. Instead the two state changes that must be atomic —
     * claiming the row, and recording the outcome — each run inside their own
     * {@link TransactionTemplate}, and the HTTP call happens in between.
     *
     * <p>The claim is what makes a redelivered queue message safe: the row goes
     * to {@code SENDING} in a transaction that commits before the call, so a
     * second consumer that picks the same message up finds it no longer
     * {@code QUEUED} and does nothing. SES has no idempotency key, so without
     * that a timeout-then-retry would email a real customer twice.
     */
    public void attempt(UUID messageId) {
        // Claim in its own transaction, and only if still queued.
        var claimed = tx.execute(status -> messages.findById(messageId)
                .filter(m -> m.getStatus() == EmailMessage.Status.QUEUED)
                .map(m -> {
                    m.claimAttempt();
                    return messages.save(m);
                })
                .orElse(null));
        if (claimed == null) {
            return;   // already handled, or a redelivery of an acked queue message
        }

        String recipient = claimed.getRecipientEmail();
        String subjectLine = claimed.getSubjectLine();
        String bodyText = claimed.getBodyText();
        SubjectType subjectType = claimed.getSubjectType();
        UUID subjectId = claimed.getSubjectId();
        String templateCode = claimed.getTemplateCode();

        EmailGateway.SendResult outcome;
        try {
            outcome = gateway.send(new EmailGateway.SendCommand(
                    recipient, subjectLine, bodyText, null, null, messageId.toString()));
        } catch (RuntimeException e) {
            // A thrown exception is an unclassified failure. Treat it as
            // retryable: the row stays QUEUED and the budget below decides,
            // rather than a network glitch permanently killing the message.
            outcome = EmailGateway.SendResult.failure(e.getClass().getSimpleName(),
                    String.valueOf(e.getMessage()), true);
        }
        final EmailGateway.SendResult result = outcome;

        tx.executeWithoutResult(status -> recordOutcome(messageId, subjectType, subjectId,
                templateCode, recipient, subjectLine, bodyText, result));
    }

    /** Second half of {@link #attempt}: persist the outcome, in one transaction. */
    private void recordOutcome(UUID messageId, SubjectType subjectType, UUID subjectId,
                               String templateCode, String recipient, String subjectLine,
                               String bodyText, EmailGateway.SendResult result) {
        EmailMessage row = messages.findById(messageId).orElse(null);
        if (row == null) {
            return;
        }
        if (result.success()) {
            row.markSent(result.providerMessageId(), Instant.now());
            timeline.save(TimelineEvent.outboundTemplate(subjectType, subjectId,
                    TimelineEvent.Kind.TEMPLATE_SENT, templateCode,
                    "Sent email to " + recipient, gateway.provider(),
                    result.providerMessageId(), null, null));
            threads.recordOutbound(subjectType, subjectId, CommunicationChannel.EMAIL,
                    null, null, subjectLine == null ? bodyText : subjectLine, Instant.now());
        } else if (!result.retryable()) {
            row.markBounced(result.failureReason(), Instant.now());
            log.warn("[email] permanent failure for {}: {}", messageId, result.failureReason());
            timeline.save(TimelineEvent.systemNote(subjectType, subjectId,
                    "Email rejected by provider: " + result.failureReason(), null));
        } else if (row.getAttempts() >= MAX_ATTEMPTS) {
            // Budget spent. Dead-letter it rather than leaving a row that looks
            // sendable forever and is silently retried by the next sweep.
            // One call, not markDeadLettered() then markFailed(): the latter
            // resets the status back to FAILED, which reads as "still owed an
            // attempt" and would be picked up again by the recovery sweep.
            row.markDeadLettered(result.error(), result.failureReason());
            log.warn("[email] {} exhausted {} attempts, dead-lettered: {}",
                    messageId, MAX_ATTEMPTS, result.error());
            timeline.save(TimelineEvent.systemNote(subjectType, subjectId,
                    "Email failed after " + MAX_ATTEMPTS + " attempts: " + result.error(), null));
        } else {
            // Back to QUEUED: this row is still owed an attempt, which is what
            // the routing listener and the recovery sweep both look for.
            row.markFailed(result.error(), result.failureReason());
            row.releaseForRetry();
            log.warn("[email] retryable failure for {} (attempt {} of {}): {}",
                    messageId, row.getAttempts(), MAX_ATTEMPTS, result.error());
        }
        messages.save(row);
    }

    /**
     * Whether the row is still owed an attempt. The inline retry loop asks after
     * every attempt so a permanent rejection stops the loop immediately instead
     * of burning the remaining budget on an address the provider already
     * refused.
     *
     * <p>{@code REQUIRES_NEW}, always. The caller is an {@code AFTER_COMMIT}
     * listener, where Spring still reports the just-finished transaction as
     * active; a {@code REQUIRED} read joins that phantom transaction and serves
     * the stale row from its first-level cache, so the loop would burn every
     * retry believing the message was still {@code QUEUED} — exactly what the
     * WARN below observed on every send. It also has to see the outcome the
     * {@code attempt} wrote, which only a fresh session can.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isStillQueued(UUID messageId) {
        return messages.findById(messageId)
                .map(m -> m.getStatus() == EmailMessage.Status.QUEUED)
                .orElse(false);
    }

    static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("Email recipient is required");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        // Deliberately minimal: a full RFC 5322 validator rejects addresses
        // SES accepts, and the provider is the real authority on deliverability.
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new BadRequestException("Invalid email recipient: " + raw);
        }
        return email;
    }

    /**
     * Render {@code {{1}}..{{4}} positionally, matching the WhatsApp convention
     * so one body-value list works on every channel.
     */
    static String render(String template, List<String> values) {
        if (template == null) {
            return null;
        }
        String out = template;
        for (int i = 0; i < values.size(); i++) {
            out = out.replace("{{" + (i + 1) + "}}", values.get(i));
        }
        return out;
    }
}
