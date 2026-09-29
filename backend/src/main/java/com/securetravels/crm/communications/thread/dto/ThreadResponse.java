package com.securetravels.crm.communications.thread.dto;

import com.securetravels.crm.communications.SubjectType;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.communications.thread.ThreadStatus;

import java.time.Instant;
import java.util.UUID;

/** One row of the unified inbox. */
public record ThreadResponse(
        UUID id,
        SubjectType subjectType,
        UUID subjectId,
        CommunicationChannel channel,
        String customerMobile,
        String customerName,
        ThreadStatus status,
        int unreadCount,
        UUID assignedTo,
        Instant lastMessageAt,
        String lastDirection,
        String lastPreview,
        Instant serviceWindowExpiresAt,
        boolean templateSendAllowed
) {
}
