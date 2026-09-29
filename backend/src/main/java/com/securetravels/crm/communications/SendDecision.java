package com.securetravels.crm.communications;

import java.util.UUID;

/**
 * The send gate's verdict on a {@link SendRequest}. Not an exception type on
 * purpose: an automation (sequence step, follow-up, webhook) must be able to
 * see "marketing blocked, no consent" as data and move on, without the caller
 * needing try/catch planted around every send.
 */
public record SendDecision(
        boolean accepted,
        TimelineEvent.Channel channel,
        String reason,
        int httpStatus,
        UUID messageId
) {

    public static SendDecision accepted(TimelineEvent.Channel channel, UUID messageId) {
        return new SendDecision(true, channel, null, 202, messageId);
    }

    public static SendDecision rejected(TimelineEvent.Channel channel, int httpStatus, String reason) {
        return new SendDecision(false, channel, reason, httpStatus, null);
    }
}