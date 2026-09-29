package com.securetravels.crm.communications;

import com.securetravels.crm.communications.consent.Purpose;

import java.util.List;
import java.util.UUID;

/**
 * A request to send one message on one channel (Phase 5 Module 1).
 *
 * <p>Every outbound path constructs one of these and asks
 * {@link SendGateService} — nothing calls a channel dispatcher directly. The
 * gate decides consent and the channel adapter does the transport.
 *
 * @param purpose fallback purpose for free-form sends. For template sends the
 *                channel adapter derives the purpose from the template's
 *                category (a promotion cannot be relabelled transactional).
 */
public record SendRequest(
        SubjectType subjectType,
        UUID subjectId,
        TimelineEvent.Channel channel,
        Purpose purpose,
        String templateCode,
        String mobile,
        String email,
        List<String> bodyValues,
        UUID actorId
) {

    public static SendRequest whatsappTemplate(SubjectType subjectType, UUID subjectId,
            String templateCode, String mobile,
            List<String> bodyValues, UUID actorId) {
        return new SendRequest(subjectType, subjectId, TimelineEvent.Channel.WHATSAPP,
                Purpose.TRANSACTIONAL, templateCode, mobile, null, bodyValues, actorId);
    }

    /**
     * A WhatsApp message with no template — free text, typed by a person.
     *
     * <p>Free-form text is the one outbound shape the provider's 24h customer
     * service window forbids outside an active conversation, so it gets its own
     * factory rather than being spelled out at each call site: the window is
     * enforced in {@code SendGateService} and is easy to forget.
     */
    public static SendRequest whatsappFreeform(SubjectType subjectType, UUID subjectId,
                                               String mobile, String text, UUID actorId) {
        return new SendRequest(subjectType, subjectId, TimelineEvent.Channel.WHATSAPP,
                Purpose.TRANSACTIONAL, null, mobile, null, List.of(text), actorId);
    }

    /** An email through a cross-channel template, or free text if code is null. */
    public static SendRequest email(SubjectType subjectType, UUID subjectId, String templateCode,
                                    String recipient, List<String> bodyValues, UUID actorId) {
        return new SendRequest(subjectType, subjectId, TimelineEvent.Channel.EMAIL,
                Purpose.TRANSACTIONAL, templateCode, null, recipient, bodyValues, actorId);
    }

    /** An SMS through a cross-channel template, or free text if code is null. */
    public static SendRequest sms(SubjectType subjectType, UUID subjectId, String templateCode,
                                  String mobile, List<String> bodyValues, UUID actorId) {
        return new SendRequest(subjectType, subjectId, TimelineEvent.Channel.SMS,
                Purpose.TRANSACTIONAL, templateCode, mobile, null, bodyValues, actorId);
    }
}