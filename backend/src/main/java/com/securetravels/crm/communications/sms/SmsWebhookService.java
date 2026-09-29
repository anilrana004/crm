package com.securetravels.crm.communications.sms;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.WebhookSignatureException;
import com.securetravels.crm.common.security.HmacSigner;
import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.communications.TimelineEventRepository;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.dto.SmsWebhookEvent;
import com.securetravels.crm.communications.inbound.InboundMessage;
import com.securetravels.crm.communications.inbound.InboundMessageService;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/**
 * Handles inbound SMS webhooks: delivery receipts and customer replies
 * (Phase 5 Module 2).
 *
 * <p>SMS is a two-way channel in India by regulation, not an implementation
 * detail: TRAI requires every commercial sender to honour a STOP. That is the
 * reason this class is not just a delivery-status handler.
 *
 * <p>Replies funnel through {@link InboundMessageService#record}, the same
 * single entry point WhatsApp and email use. It owns the idempotency claim on
 * the {@code (provider, provider_message_id)} key and the sender resolution
 * (known customer, existing lead, or a lead created for a stranger whose number
 * we have never seen), so a reply and the reply's retry collapse into one row,
 * one lead, and one inbox thread. The opt-out runs <em>after</em> that claim:
 * a retried STOP finds the existing inbound row and never reaches the consent
 * call, so three gateway deliveries produce one revoke, not three.
 *
 * <p>Signature checking follows the WhatsApp webhook: a wrong signature is an
 * error we must surface, because acknowledging a forged opt-out as accepted
 * would be worse than a retry storm. A malformed body is acknowledged silently
 * since it will be malformed on retry too.
 */
@Service
public class SmsWebhookService {

    private static final Logger log = LoggerFactory.getLogger(SmsWebhookService.class);
    private static final String PROVIDER = Msg91SmsGateway.PROVIDER;

    private final SmsMessageRepository messages;
    private final TimelineEventRepository timeline;
    private final ConsentService consent;
    private final InboundMessageService inboundMessages;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public SmsWebhookService(SmsMessageRepository messages, TimelineEventRepository timeline,
                             ConsentService consent, InboundMessageService inboundMessages,
                             ObjectMapper objectMapper, AppProperties props) {
        this.messages = messages;
        this.timeline = timeline;
        this.consent = consent;
        this.inboundMessages = inboundMessages;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * @return transitions applied; 0 for a duplicate, malformed body, or an
     *         unrecognised type.
     * @throws WebhookSignatureException on a bad or missing signature.
     */
    @Transactional
    public int handle(byte[] rawBody, String providedSignature) {
        String secret = props.getSms().getWebhookSecret();
        if (secret != null && !secret.isBlank()
                && !HmacSigner.matches(providedSignature, secret, rawBody)) {
            log.warn("[sms] rejected webhook with invalid or missing signature");
            throw new WebhookSignatureException("Invalid SMS webhook signature");
        }

        SmsWebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, SmsWebhookEvent.class);
        } catch (Exception e) {
            log.warn("[sms] unparseable webhook body: {}", e.getMessage());
            return 0;
        }
        if (event == null) {
            return 0;
        }
        String type = event.type() == null ? "" : event.type().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DELIVERY_REPORT" -> onDelivery(event);
            case "INBOUND", "SMS_INBOUND", "REPLY" -> onReply(event);
            default -> 0;
        };
    }

    private int onDelivery(SmsWebhookEvent event) {
        String messageId = firstNonBlank(event.messageId(), event.id());
        if (messageId == null) {
            return 0;
        }
        Optional<SmsMessage> found = messages.findByProviderAndProviderMessageId(
                        Msg91SmsGateway.PROVIDER, messageId)
                .or(() -> messages.findByProviderAndProviderMessageId(
                        SandboxSmsGateway.PROVIDER, messageId));
        if (found.isEmpty()) {
            log.info("[sms] delivery report for unknown message {}", messageId);
            return 0;
        }
        SmsMessage row = found.get();
        if (row.getStatus() != SmsMessage.Status.SENT) {
            return 0;   // forward-only, and DELIVERED on a FAILED row is a lie
        }
        row.markDelivered(Instant.now());
        messages.save(row);
        return 1;
    }

    private int onReply(SmsWebhookEvent event) {
        String rawMobile = firstNonBlank(event.mobile(), event.from(), event.phone());
        String text = firstNonBlank(event.text(), event.body(), event.message());
        String digits = PhoneUtils.normalize(rawMobile);
        if (digits == null || text == null) {
            return 0;
        }

        // One row per provider id, on every channel, recorded in the single
        // place a redelivery can be recognised. It also resolves the sender to
        // a customer/lead (creating a lead for a stranger) and opens their inbox
        // thread. Doing this FIRST is what makes the opt-out below idempotent.
        String providerMessageId = firstNonBlank(event.messageId(), event.id());
        Optional<InboundMessage> delivered = inboundMessages.record(CommunicationChannel.SMS,
                PROVIDER, providerMessageId, digits, null, text, false, null, Instant.now());
        if (delivered.isEmpty()) {
            return 0;   // duplicate, or a message with no provider id at all
        }
        InboundMessage recorded = delivered.get();

        // A STOP revokes MARKETING consent for this number on this channel —
        // processed even for a number with no customer row (which becomes a
        // suppression), because a preference expressed once must weigh as much
        // as one expressed after an account exists.
        if (isOptOut(text)) {
            boolean revoked = consent.handleOptOut(TimelineEvent.Channel.SMS, digits,
                    "SMS message " + providerMessageId);
            log.info("[sms] opt-out \"{}\" from {} (customer consent revoked: {})",
                    text.trim(), digits, revoked);
            if (recorded.getSubjectType() != null && recorded.getSubjectId() != null) {
                timeline.save(TimelineEvent.systemNote(recorded.getSubjectType(), recorded.getSubjectId(),
                        "Marketing opt-out received and recorded", null));
            }
            return 1;
        }

        // A reply with no resolvable sender is still stored (deduped, thread
        // open); it only has no timeline row to hang off.
        if (recorded.getSubjectType() != null && recorded.getSubjectId() != null
                && !timeline.existsByProviderAndProviderMessageIdAndKind(
                        PROVIDER, providerMessageId, TimelineEvent.Kind.REPLY_RECEIVED)) {
            timeline.save(TimelineEvent.inbound(recorded.getSubjectType(), recorded.getSubjectId(),
                    TimelineEvent.Channel.SMS, TimelineEvent.Kind.REPLY_RECEIVED,
                    "Replied on SMS: " + truncate(text), text, PROVIDER, providerMessageId, digits));
        }
        return 1;
    }

    /**
     * The TRAI stop phrases, matched on squashed upper-case so "stop", "STOP."
     * and "STOP 12345" all resolve identically.
     */
    static boolean isOptOut(String text) {
        if (text == null) {
            return false;
        }
        String squashed = text.toUpperCase().replaceAll("[^A-Z]", "");
        return squashed.equals("STOP") || squashed.equals("UNSUBSCRIBE") || squashed.equals("UNSUB")
                || squashed.equals("OPTOUT") || squashed.equals("QUIT") || squashed.equals("CANCEL");
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "\u2026";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
