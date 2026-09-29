package com.securetravels.crm.communications.email;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.exception.WebhookSignatureException;
import com.securetravels.crm.common.security.HmacSigner;
import com.securetravels.crm.communications.TimelineEvent;
import com.securetravels.crm.communications.TimelineEventRepository;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.dto.EmailWebhookEvent;
import com.securetravels.crm.communications.inbound.InboundMessage;
import com.securetravels.crm.communications.inbound.InboundMessageService;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Handles SES delivery notifications: delivery, open, bounce, complaint
 * (Phase 5 Module 2).
 *
 * <p>Two of these events are consent decisions, not just delivery state:
 *
 * <ul>
 *   <li><b>BOUNCE</b> on a marketing email — the address does not exist. Keeping
 *       marketing consent "granted" against a dead address is a data-quality
 *       lie, and repeated sends to a hard bounce damage the sending reputation
 *       for every other customer.</li>
 *   <li><b>COMPLAINT</b> — a spam report. That is an explicit opt-out in
 *       stronger words than a link click, and it must revoke consent
 *       immediately.</li>
 * </ul>
 *
 * <p>Both are applied through {@link ConsentService} rather than by editing a
 * flag, so the history shows why. Idempotency is the unique
 * {@code (provider, provider_message_id, kind)} index on timeline_events plus the
 * forward-only state check, because SES retries notifications at least once and
 * may deliver them out of order.
 */
@Service
public class EmailWebhookService {

    private static final Logger log = LoggerFactory.getLogger(EmailWebhookService.class);
    private static final String PROVIDER = SesEmailGateway.PROVIDER;

    /** Terminal states: a late delivery event must not resurrect a bounce. */
    private static final Set<EmailMessage.Status> TERMINAL =
            Set.of(EmailMessage.Status.BOUNCED, EmailMessage.Status.COMPLAINED,
                   EmailMessage.Status.DELIVERED, EmailMessage.Status.OPENED);

    private final EmailMessageRepository messages;
    private final TimelineEventRepository timeline;
    private final ConsentService consent;
    private final InboundMessageService inboundMessages;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public EmailWebhookService(EmailMessageRepository messages, TimelineEventRepository timeline,
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
     * @return state transitions actually applied; 0 for a duplicate, a malformed
     *         body, or an event for an unknown message.
     * @throws WebhookSignatureException if the signature is absent or wrong.
     *         This deliberately is an error: a 200 would tell SES we read a
     *         notification we never verified.
     */
    @Transactional
    public int handle(byte[] rawBody, String providedSignature) {
        String secret = props.getEmail().getWebhookSecret();
        if (secret != null && !secret.isBlank()
                && !HmacSigner.matches(providedSignature, secret, rawBody)) {
            log.warn("[email] rejected SES notification with invalid or missing signature");
            throw new WebhookSignatureException("Invalid email webhook signature");
        }

        EmailWebhookEvent event;
        try {
            event = objectMapper.readValue(rawBody, EmailWebhookEvent.class);
        } catch (Exception e) {
            log.warn("[email] unparseable SES notification: {}", e.getMessage());
            return 0;   // malformed on retry too: acknowledge rather than loop
        }
        if (event == null || event.mail() == null || event.mail().messageId() == null) {
            return 0;
        }
        String type = event.notificationType() == null ? "" : event.notificationType().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "DELIVERY" -> apply(event, EmailMessage.Status.DELIVERED);
            case "OPEN" -> apply(event, EmailMessage.Status.OPENED);
            case "BOUNCE", "COMPLAINT" -> apply(event,
                    type.equals("BOUNCE") ? EmailMessage.Status.BOUNCED : EmailMessage.Status.COMPLAINED);
            case "RECEIVED" -> onReceived(event);
            default -> 0;
        };
    }

    private int apply(EmailWebhookEvent event, EmailMessage.Status next) {
        String providerMessageId = event.mail().messageId();
        EmailMessage row = messages.findByProviderAndProviderMessageId(PROVIDER, providerMessageId)
                .orElseGet(() -> messages.findByProviderAndProviderMessageId(
                        SandboxEmailGateway.PROVIDER, providerMessageId).orElse(null));
        if (row == null) {
            log.info("[email] notification for unknown message {}", providerMessageId);
            return 0;
        }
        if (TERMINAL.contains(row.getStatus())) {
            return 0;   // forward-only: a late DELIVERY after a BOUNCE is noise
        }

        Instant at = parseTimestamp(event.mail().timestamp());
        switch (next) {
            case DELIVERED -> row.markDelivered(at);
            case OPENED -> row.markOpened(at);
            case BOUNCED -> row.markBounced(event.mail().bounceType() == null
                    ? "hard bounce" : event.mail().bounceType().toLowerCase(Locale.ROOT) + " bounce", at);
            case COMPLAINED -> row.markComplained("spam complaint");
            default -> { return 0; }
        }
        messages.save(row);

        TimelineEvent.Kind kind = switch (next) {
            case DELIVERED -> TimelineEvent.Kind.TEMPLATE_DELIVERED;
            case OPENED -> TimelineEvent.Kind.TEMPLATE_OPENED;
            case BOUNCED -> TimelineEvent.Kind.TEMPLATE_BOUNCED;
            default -> TimelineEvent.Kind.SYSTEM_NOTE;
        };
        if (!timeline.existsByProviderAndProviderMessageIdAndKind(PROVIDER, providerMessageId, kind)) {
            timeline.save(TimelineEvent.systemNote(row.getSubjectType(), row.getSubjectId(),
                    "Email " + next.name().toLowerCase(Locale.ROOT)
                            + (row.getFailureReason() == null ? "" : ": " + row.getFailureReason()), null));
        }

        if (next.isNegativeSignal()) {
            revokeEmailConsent(row, providerMessageId);
        }
        return 1;
    }

    /**
     * A customer reply that SES forwarded (notificationType {@code Received}).
     *
     * <p>Runs through {@link InboundMessageService#record}, the same single
     * entry point WhatsApp and SMS use, so a retried SES delivery recognises the
     * duplicate id, the sender resolves to a customer/lead (or is stored
     * unlinked when we only have an address), and the inbox thread opens exactly
     * once. The {@code (provider, provider_message_id)} claim is what makes the
     * opt-out keyword below a single revocation instead of one per retry.
     */
    private int onReceived(EmailWebhookEvent event) {
        String messageId = event.mail().messageId();
        String from = senderOf(event);
        EmailWebhookEvent.CommonHeaders headers = event.mail().commonHeaders();
        String subject = headers == null ? null : headers.subject();
        String text = plainBody(event, subject);
        if (from == null || text == null) {
            return 0;
        }

        Optional<InboundMessage> delivered = inboundMessages.record(CommunicationChannel.EMAIL,
                PROVIDER, messageId, null, from, text, false, null,
                parseTimestamp(event.mail().timestamp()));
        if (delivered.isEmpty()) {
            return 0;
        }
        InboundMessage recorded = delivered.get();

        // A reply of "stop"/"unsubscribe" is an instruction, not a conversation.
        // Only the first delivery reaches the consent path: three SES retries of
        // one message must revoke once, not three times. The keyword is matched
        // against the subject or the reply's own first line, not the whole quoted
        // thread this message will contain.
        if (isOptOut(subject) || isOptOut(firstLine(text))) {
            consent.suppressEmailAddress(from, "reply keyword from " + from
                    + " on SES message " + messageId);
            log.info("[email] opt-out keyword in reply from {}", from);
            if (recorded.getSubjectType() != null && recorded.getSubjectId() != null) {
                timeline.save(TimelineEvent.systemNote(recorded.getSubjectType(), recorded.getSubjectId(),
                        "Marketing opt-out received and recorded", null));
            }
            return 1;
        }

        if (recorded.getSubjectType() != null && recorded.getSubjectId() != null
                && !timeline.existsByProviderAndProviderMessageIdAndKind(
                        PROVIDER, messageId, TimelineEvent.Kind.REPLY_RECEIVED)) {
            timeline.save(TimelineEvent.inbound(recorded.getSubjectType(), recorded.getSubjectId(),
                    TimelineEvent.Channel.EMAIL, TimelineEvent.Kind.REPLY_RECEIVED,
                    "Replied on email: " + truncate(text), text, PROVIDER, messageId, from));
        }
        return 1;
    }

    /** SES's envelope {@code source} is the reliable sender; fall back to the
     *  From header when the source is absent (sandboxed payloads often omit it). */
    private static String senderOf(EmailWebhookEvent event) {
        String source = event.mail().source();
        if (source != null && !source.isBlank()) {
            return source.trim().toLowerCase();
        }
        EmailWebhookEvent.CommonHeaders headers = event.mail().commonHeaders();
        if (headers != null && headers.from() != null && !headers.from().isEmpty()) {
            String header = headers.from().get(0);
            int lt = header.lastIndexOf('<');
            int gt = header.lastIndexOf('>');
            if (lt >= 0 && gt > lt) {
                return header.substring(lt + 1, gt).trim().toLowerCase();
            }
            return header.trim().toLowerCase();
        }
        return null;
    }

    /**
     * SES ships the full RFC822 message as base64 {@code content}. Peel the
     * headers off it and return the body; any failure degrades gracefully to the
     * subject line so a malformed payload is stored rather than dropped.
     */
    private static String plainBody(EmailWebhookEvent event, String fallbackSubject) {
        String content = event.content();
        if (content == null || content.isBlank()) {
            return fallbackSubject;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(content.trim());
            String raw = new String(decoded, StandardCharsets.UTF_8);
            int marker = Math.max(raw.indexOf("\n\n"), raw.indexOf("\r\n\r\n"));
            String body = marker >= 0 ? raw.substring(marker).trim() : raw.trim();
            return body.isEmpty() ? fallbackSubject : body;
        } catch (IllegalArgumentException e) {
            log.warn("[email] unreadable base64 content for {}", event.mail().messageId());
            return fallbackSubject;
        }
    }

    private static boolean isOptOut(String text) {
        if (text == null) {
            return false;
        }
        String squashed = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z]", "");
        return squashed.equals("STOP") || squashed.equals("UNSUBSCRIBE") || squashed.equals("UNSUB")
                || squashed.equals("OPTOUT") || squashed.equals("QUIT") || squashed.equals("CANCEL");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "\u2026";
    }

    /**
     * A dead or complained-about address is not a marketing audience. This only
     * applies where we can find the person: a bounce for an address with no
     * customer row is still recorded on the message, but there is nobody to
     * attach a consent decision to.
     */
    private void revokeEmailConsent(EmailMessage row, String providerMessageId) {
        consent.suppressEmailAddress(row.getRecipientEmail(), "SES " + row.getStatus().name().toLowerCase(Locale.ROOT)
                + " for message " + providerMessageId);
        log.info("[email] marketing consent revoked for bounced/complained address {}",
                row.getRecipientEmail());
    }

    private static Instant parseTimestamp(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return Instant.now();   // a malformed timestamp must not drop the event
        }
    }
}
