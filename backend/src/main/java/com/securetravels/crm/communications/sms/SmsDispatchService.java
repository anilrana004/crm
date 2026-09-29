package com.securetravels.crm.communications.sms;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.util.PhoneUtils;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * SMS's channel adapter: the gate's entry point for outbound SMS
 * (Phase 5 Module 2).
 *
 * <p>Structurally identical to {@code EmailDispatchService} — intent row first,
 * provider call after commit, one retry policy — because a channel that behaved
 * differently would mean every "just send an SMS" call site had to remember
 * which rules applied to it.
 *
 * <p>Unlike the other two channels, SMS is expected to be transactional and
 * length-bounded in practice: an OTP or a balance reminder. That is enforced by
 * the template catalogue (a PENDING template cannot be sent at all) rather than
 * by a length rule here, because carrier segment limits and DLT registration
 * constraints are the real constraints and they change.
 */
@Service
public class SmsDispatchService implements OutboundChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SmsDispatchService.class);

    /**
     * Total attempts a row is owed before it is dead-lettered.
     *
     * <p>Enforced here, where the attempt count is durable, rather than in
     * {@code SmsRoutingListener}'s loop, so switching to a real broker does not
     * silently change how many times a customer is texted.
     */
    static final int MAX_ATTEMPTS = 3;

    private final SmsMessageRepository messages;
    private final ChannelTemplateService templates;
    private final TimelineEventRepository timeline;
    private final CommunicationThreadService threads;
    private final SmsGateway gateway;
    private final AppProperties props;
    private final org.springframework.context.ApplicationEventPublisher events;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public SmsDispatchService(SmsMessageRepository messages, ChannelTemplateService templates,
                              TimelineEventRepository timeline, CommunicationThreadService threads,
                              SmsGateway gateway, AppProperties props,
                              org.springframework.context.ApplicationEventPublisher events,
                              org.springframework.transaction.support.TransactionTemplate tx) {
        this.messages = messages;
        this.templates = templates;
        this.timeline = timeline;
        this.threads = threads;
        this.gateway = gateway;
        this.props = props;
        this.events = events;
        // REQUIRES_NEW, for the same reason as WhatsAppSender and
        // EmailDispatchService: the main caller is SmsRoutingListener, which
        // runs AFTER_COMMIT, where Spring still reports the completed
        // transaction as active. A default REQUIRED template would join that
        // phantom transaction and the outcome write would be discarded, leaving
        // every SMS at QUEUED even though the gateway had accepted it.
        this.tx = new org.springframework.transaction.support.TransactionTemplate(
                tx.getTransactionManager());
        this.tx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public TimelineEvent.Channel channel() {
        return TimelineEvent.Channel.SMS;
    }

    @Override
    public Purpose resolvePurpose(SendRequest request) {
        if (request.templateCode() == null) {
            return request.purpose() == null ? Purpose.TRANSACTIONAL : request.purpose();
        }
        return templates.purposeOf(CommunicationChannel.SMS, request.templateCode());
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID dispatch(SendRequest request) {
        return enqueue(request.subjectType(), request.subjectId(), request.templateCode(),
                request.mobile(), request.bodyValues(), request.actorId()).getId();
    }

    SmsMessage enqueue(SubjectType subjectType, UUID subjectId, String templateCode,
                        String mobile, List<String> bodyValues, UUID actorId) {
        String digits = PhoneUtils.normalize(mobile);
        if (digits == null) {
            throw new BadRequestException("Invalid SMS recipient mobile: " + mobile);
        }
        List<String> values = bodyValues == null ? List.of() : List.copyOf(bodyValues);

        String body;
        String dltTemplateId = null;
        if (templateCode != null) {
            ChannelTemplate template = templates.require(CommunicationChannel.SMS, templateCode);
            if (template.getApprovalStatus() != ChannelTemplate.ApprovalStatus.APPROVED) {
                throw new BadRequestException("SMS template " + templateCode
                        + " is not approved (status " + template.getApprovalStatus() + ")");
            }
            if (values.size() != template.getExpectedParams()) {
                throw new BadRequestException("Template " + templateCode + " expects "
                        + template.getExpectedParams() + " body value(s) but " + values.size() + " were supplied");
            }
            body = render(template.getBody(), values);
            dltTemplateId = template.getProviderRef();
        } else {
            body = String.join(" ", values);
        }
        if (body == null || body.isBlank()) {
            throw new BadRequestException("Refusing to send an SMS with an empty body");
        }

        var thread = threads.resolve(subjectType, subjectId, CommunicationChannel.SMS, digits, null);

        SmsMessage row = new SmsMessage();
        row.setThread(thread);
        row.setSubjectType(subjectType);
        row.setSubjectId(subjectId);
        row.setTemplateCode(templateCode);
        row.setRecipientMobile(digits);
        row.setCountryCode(props.getWhatsApp().getCountryCode());
        row.setBodyText(body);
        row.setDltTemplateId(dltTemplateId);
        row.setProvider(gateway.provider());
        SmsMessage saved = messages.save(row);

        timeline.save(TimelineEvent.outboundTemplate(subjectType, subjectId,
                TimelineEvent.Kind.TEMPLATE_QUEUED, templateCode,
                "Queued SMS to " + digits, gateway.provider(), null, digits, values));

        SmsMessage savedRow = saved;
        events.publishEvent(new SmsQueuedEvent(savedRow.getId()));
        return savedRow;
    }

    /**
     * One send attempt for a row that is still {@code QUEUED}.
     *
     * <p>Mirrors {@code EmailDispatchService.attempt} exactly, and for the same
     * two reasons. It is deliberately <em>not</em> {@code @Transactional}: a
     * gateway call is a network round trip that can take seconds or hang, and
     * holding a pooled connection and an open transaction across it exhausts the
     * pool under any burst of sends. The two state changes that must be atomic
     * — claiming the row, and recording the outcome — each get their own
     * transaction, with the HTTP call in between.
     *
     * <p>Claiming before the call is what makes a redelivered queue message
     * safe: the row leaves {@code QUEUED} in a transaction that commits first, so
     * a second consumer finds it claimed and does nothing. MSG91 offers no
     * idempotency key, so without that a timeout-then-retry would bill and text a
     * real customer twice.
     */
    public void attempt(UUID messageId) {
        var claimed = tx.execute(status -> messages.findById(messageId)
                .filter(m -> m.getStatus() == SmsMessage.Status.QUEUED)
                .map(m -> {
                    m.claimAttempt();
                    return messages.save(m);
                })
                .orElse(null));
        if (claimed == null) {
            return;   // already handled, or a redelivery of an acked queue message
        }

        SmsGateway.SendResult outcome;
        try {
            outcome = gateway.send(new SmsGateway.SendCommand(
                    claimed.getCountryCode(), claimed.getRecipientMobile(), claimed.getBodyText(),
                    props.getSms().getSenderId(), claimed.getDltTemplateId()));
        } catch (RuntimeException e) {
            // An unclassified failure. Treat it as retryable so a network glitch
            // does not permanently kill a message to a paying customer; the
            // budget below decides when to stop.
            outcome = SmsGateway.SendResult.failure(e.getClass().getSimpleName(),
                    String.valueOf(e.getMessage()), true);
        }
        final SmsGateway.SendResult result = outcome;

        tx.executeWithoutResult(status -> recordOutcome(messageId, result));
    }

    /** Second half of {@link #attempt}: persist the outcome, in one transaction. */
    private void recordOutcome(UUID messageId, SmsGateway.SendResult result) {
        SmsMessage row = messages.findById(messageId).orElse(null);
        if (row == null) {
            return;
        }
        if (result.success()) {
            row.markSent(result.providerMessageId(), Instant.now());
            timeline.save(TimelineEvent.outboundTemplate(row.getSubjectType(), row.getSubjectId(),
                    TimelineEvent.Kind.TEMPLATE_SENT, row.getTemplateCode(),
                    "Sent SMS to " + row.getRecipientMobile(), gateway.provider(),
                    result.providerMessageId(), row.getRecipientMobile(), null));
            threads.recordOutbound(row.getSubjectType(), row.getSubjectId(), CommunicationChannel.SMS,
                    row.getRecipientMobile(), null, row.getBodyText(), Instant.now());
        } else if (!result.retryable()) {
            // A rejected DLT template or an invalid number will be rejected
            // identically forever; retrying only spends money to reach the same
            // error, and the operator needs to see FAILED to fix the template.
            row.markFailed(result.error(), result.failureReason());
            log.warn("[sms] permanent failure for {}: {}", messageId, result.failureReason());
            timeline.save(TimelineEvent.systemNote(row.getSubjectType(), row.getSubjectId(),
                    "SMS rejected by provider: " + result.failureReason(), null));
        } else if (row.getAttempts() >= MAX_ATTEMPTS) {
            row.markDeadLettered(result.error(), result.failureReason());
            log.warn("[sms] {} exhausted {} attempts, dead-lettered: {}",
                    messageId, MAX_ATTEMPTS, result.error());
            timeline.save(TimelineEvent.systemNote(row.getSubjectType(), row.getSubjectId(),
                    "SMS failed after " + MAX_ATTEMPTS + " attempts: " + result.error(), null));
        } else {
            // Back to QUEUED: still owed an attempt. This is what the routing
            // listener's loop and the recovery sweep both look for -- without it
            // a single transient 5xx would end the message after one try.
            row.markFailed(result.error(), result.failureReason());
            row.releaseForRetry();
            log.warn("[sms] retryable failure for {} (attempt {} of {}): {}",
                    messageId, row.getAttempts(), MAX_ATTEMPTS, result.error());
        }
        messages.save(row);
    }

    /** @see EmailDispatchService#isStillQueued */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean isStillQueued(UUID messageId) {
        return messages.findById(messageId)
                .map(m -> m.getStatus() == SmsMessage.Status.QUEUED)
                .orElse(false);
    }

    /** Positional {@code {{1}}..{{4}}}, the same convention as every channel. */
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
