package com.securetravels.crm.communications;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.WebhookSignatureException;
import com.securetravels.crm.common.security.HmacSigner;
import com.securetravels.crm.common.util.PhoneUtils;
import com.securetravels.crm.communications.dto.InteraktWebhookEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Handles Interakt's inbound webhook: delivery status and customer replies
 * (Module 4).
 *
 * <p>Two rules shape everything here:
 *
 * <ol>
 *   <li><b>Always answer 200 for anything we understand.</b> Interakt retries
 *       any non-2xx, so returning an error for an <em>unrecognised</em> event
 *       type would generate retries forever. Unknown types are logged and
 *       acknowledged.</li>
 *   <li><b>Handled events must be idempotent.</b> Interakt retries on timeout
 *       and we may also be re-delivered after a restart, so a repeated
 *       {@code message_api_delivered} must not append a second timeline row.
 *       The {@code (provider, provider_message_id, kind)} unique index is the
 *       backstop; the existence check avoids turning a duplicate into a 500 that
 *       provokes a further retry.</li>
 * </ol>
 *
 * <p>Outbound events are correlated by the {@code callback_data} token we set at
 * send time, falling back to the provider message id. Inbound replies carry
 * neither, so they are attributed by phone number to the subject of our most
 * recent message to that number.
 */
@Service
public class InteraktWebhookService {

    private static final Logger log = LoggerFactory.getLogger(InteraktWebhookService.class);

    private static final String PROVIDER = InteraktWhatsAppGateway.PROVIDER;

    private final WhatsAppMessageRepository messages;
    private final TimelineEventRepository timeline;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public InteraktWebhookService(WhatsAppMessageRepository messages, TimelineEventRepository timeline,
                                  ObjectMapper objectMapper, AppProperties props) {
        this.messages = messages;
        this.timeline = timeline;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    /**
     * @return the number of state transitions actually applied; {@code 0} for a
     *         duplicate, a status event for an unknown message, or an
     *         unrecognised event type.
     * @throws com.securetravels.crm.common.exception.WebhookSignatureException
     *         if the signature is absent or wrong. This deliberately *is* an
     *         error: acknowledging a forged request with a 200 would tell the
     *         sender we accepted a message we never read.
     */
    @Transactional
    public int handle(byte[] rawBody, String providedSignature) {
        if (!HmacSigner.matches(providedSignature, props.getWhatsApp().getWebhookSecret(), rawBody)) {
            log.warn("[interakt] rejected webhook with invalid or missing signature");
            throw new WebhookSignatureException("Invalid Interakt webhook signature");
        }

        InteraktWebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, InteraktWebhookEvent.class);
        } catch (Exception e) {
            // A malformed body will be malformed on retry too; acknowledge it.
            log.warn("[interakt] unparseable webhook body: {}", e.getMessage());
            return 0;
        }
        if (event == null || event.type() == null) {
            log.warn("[interakt] webhook with no type; ignoring");
            return 0;
        }

        return switch (event.type()) {
            case "message_received" -> onReply(event);
            case "message_api_clicked", "message_campaign_clicked" -> onClick(event);
            default -> onStatus(event);
        };
    }

    // ---- delivery status ----

    private int onStatus(InteraktWebhookEvent event) {
        InteraktWebhookEvent.Data data = event.data();
        if (data == null || data.message() == null) {
            log.debug("[interakt] status event {} with no message payload", event.type());
            return 0;
        }
        InteraktWebhookEvent.Message payload = data.message();

        Optional<WhatsAppMessage> found = correlate(payload);
        if (found.isEmpty()) {
            // A sandbox message has no Interakt id, and a status event for a
            // message we never sent is not actionable. Not an error.
            log.debug("[interakt] status {} for uncorrelated message id={}", event.type(), payload.id());
            return 0;
        }
        WhatsAppMessage message = found.get();

        // Interakt's own message_status is a string ("Sent"/"Delivered"/"Read"/"Failed").
        // The event type is the reliable signal, so branch on that.
        if (event.type().endsWith("_delivered")) {
            if (message.getStatus() == WhatsAppMessage.Status.DELIVERED
                    || message.getStatus() == WhatsAppMessage.Status.READ) {
                return 0;
            }
            message.markDelivered(Instant.now());
            messages.save(message);
            return logOnce(message, TimelineEvent.Kind.TEMPLATE_DELIVERED,
                    "Delivered " + message.getTemplateCode() + " to " + message.getRecipientMobile());

        }
        if (event.type().endsWith("_read")) {
            if (message.getStatus() == WhatsAppMessage.Status.READ) {
                return 0;
            }
            message.markRead(Instant.now());
            messages.save(message);
            return logOnce(message, TimelineEvent.Kind.TEMPLATE_READ,
                    "Read " + message.getTemplateCode() + " by " + message.getRecipientMobile());
        }
        if (event.type().endsWith("_failed")) {
            if (message.getStatus() == WhatsAppMessage.Status.FAILED
                    || message.getStatus() == WhatsAppMessage.Status.DEAD_LETTERED) {
                return 0;
            }
            String reason = payload.channelFailureReason() == null
                    ? "rejected by WhatsApp" : payload.channelFailureReason();
            message.markFailed(reason, payload.channelErrorCode(), reason);
            messages.save(message);
            return logOnce(message, TimelineEvent.Kind.TEMPLATE_FAILED,
                    "Failed " + message.getTemplateCode() + " to " + message.getRecipientMobile()
                            + ": " + reason);
        }
        if (event.type().endsWith("_sent")) {
            // We already record SENT synchronously; this only backfills a
            // message whose send response was lost.
            if (message.getStatus() == WhatsAppMessage.Status.SENT
                    || message.getStatus().isTerminal()) {
                return 0;
            }
            message.markSent(payload.id(), Instant.now());
            messages.save(message);
            return logOnce(message, TimelineEvent.Kind.TEMPLATE_SENT,
                    "Sent " + message.getTemplateCode() + " to " + message.getRecipientMobile());
        }

        log.debug("[interakt] ignoring unrecognised status type {}", event.type());
        return 0;
    }

    // ---- inbound ----

    private int onReply(InteraktWebhookEvent event) {
        InteraktWebhookEvent.Data data = event.data();
        if (data == null || data.message() == null) return 0;
        InteraktWebhookEvent.Message payload = data.message();

        String mobile = recipientDigits(data);
        String text = payload.inboundText();
        if (mobile == null && text == null) return 0;

        Subject subject = resolveSubject(mobile);
        if (subject == null) {
            log.info("[interakt] inbound message from {} with no known conversation; not attached to a timeline",
                    mobile);
            return 0;
        }

        boolean media = payload.mediaUrl() != null;
        TimelineEvent.Kind kind = media ? TimelineEvent.Kind.MEDIA_RECEIVED : TimelineEvent.Kind.REPLY_RECEIVED;
        String summary = media
                ? "Received a WhatsApp " + (payload.messageContentType() == null ? "media" : payload.messageContentType())
                : "Replied on WhatsApp: " + truncate(text);

        if (payload.id() != null && timeline.existsByProviderAndProviderMessageIdAndKind(
                PROVIDER, payload.id(), kind)) {
            return 0;   // duplicate delivery
        }
        timeline.save(TimelineEvent.inbound(subject.type(), subject.id(), kind, summary, text,
                PROVIDER, payload.id(), mobile));
        return 1;
    }

    private int onClick(InteraktWebhookEvent event) {
        InteraktWebhookEvent.Data data = event.data();
        if (data == null || data.message() == null) return 0;
        InteraktWebhookEvent.Message payload = data.message();

        // Interakt's docs put click fields in two different places.
        InteraktWebhookEvent.Event click = data.event() != null ? data.event() : null;
        String buttonText = click != null ? click.buttonText() : null;

        Subject subject = resolveSubject(recipientDigits(data));
        if (subject == null || payload.id() == null) return 0;
        if (timeline.existsByProviderAndProviderMessageIdAndKind(
                PROVIDER, payload.id(), TimelineEvent.Kind.BUTTON_CLICKED)) {
            return 0;
        }
        timeline.save(TimelineEvent.inbound(subject.type(), subject.id(),
                TimelineEvent.Kind.BUTTON_CLICKED,
                buttonText == null ? "Clicked a button on a WhatsApp message"
                        : "Clicked \"" + truncate(buttonText) + "\" on a WhatsApp message",
                null, PROVIDER, payload.id(), recipientDigits(data)));
        return 1;
    }

    // ---- helpers ----

    /** Prefer our correlation token; fall back to the provider's message id. */
    private Optional<WhatsAppMessage> correlate(InteraktWebhookEvent.Message payload) {
        if (payload.metaData() != null && payload.metaData().sourceData() != null) {
            String callbackData = payload.metaData().sourceData().callbackData();
            if (callbackData != null && !callbackData.isBlank()) {
                Optional<WhatsAppMessage> byToken = messages.findByCallbackData(callbackData);
                if (byToken.isPresent()) return byToken;
            }
        }
        if (payload.id() != null) {
            return messages.findByProviderAndProviderMessageId(PROVIDER, payload.id());
        }
        return Optional.empty();
    }

    private String recipientDigits(InteraktWebhookEvent.Data data) {
        if (data.customer() == null) return null;
        return PhoneUtils.normalize(data.customer().phoneNumber());
    }

    /**
     * Attribute an inbound message to the conversation it belongs to: the
     * subject of our most recent outbound message to that number. A reply has no
     * other trustworthy link back to a Lead/Booking, and "the last thing we
     * said to them" is the best available answer.
     */
    private Subject resolveSubject(String mobile) {
        if (mobile == null) return null;
        return messages.findFirstByRecipientMobileOrderByQueuedAtDesc(mobile)
                .map(m -> new Subject(m.getSubjectType(), m.getSubjectId()))
                .orElse(null);
    }

    /**
     * Append a timeline row, skipping one we have already recorded for this
     * provider message. The existence check exists so a duplicate becomes a
     * no-op rather than a unique-constraint violation surfacing as a 500 — which
     * would make Interakt retry a delivery that has, in fact, succeeded.
     */
    private int logOnce(WhatsAppMessage message, TimelineEvent.Kind kind, String summary) {
        if (message.getProviderMessageId() != null
                && timeline.existsByProviderAndProviderMessageIdAndKind(
                        PROVIDER, message.getProviderMessageId(), kind)) {
            return 0;
        }
        timeline.save(TimelineEvent.outboundTemplate(
                message.getSubjectType(), message.getSubjectId(), kind, message.getTemplateCode(),
                summary, PROVIDER, message.getProviderMessageId(), message.getRecipientMobile(),
                message.bodyValues()));
        return 1;
    }

    private static String truncate(String value) {
        if (value == null) return "";
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }

    private record Subject(SubjectType type, UUID id) {
    }
}
