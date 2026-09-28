package com.securetravels.crm.communications.dto;

import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.WhatsAppMessage;

import java.time.Instant;
import java.util.UUID;

public record WhatsAppMessageResponse(
        UUID id,
        SubjectType subjectType,
        UUID subjectId,
        String templateCode,
        String recipientMobile,
        String status,
        int attempts,
        String lastError,
        String channelErrorCode,
        Instant queuedAt,
        Instant sentAt,
        Instant deliveredAt,
        Instant readAt) {

    public static WhatsAppMessageResponse from(WhatsAppMessage m) {
        return new WhatsAppMessageResponse(m.getId(), m.getSubjectType(), m.getSubjectId(),
                m.getTemplateCode(), m.getRecipientMobile(), m.getStatus().name(), m.getAttempts(),
                m.getLastError(), m.getChannelErrorCode(), m.getQueuedAt(), m.getSentAt(),
                m.getDeliveredAt(), m.getReadAt());
    }
}
