package com.securetravels.crm.communications;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Operator-initiated outbound SMS (Phase 5 Module 2).
 *
 * <p>Same shape as {@code EmailSendRequest} on purpose. The mobile is validated
 * as ten Indian digits rather than left loose, because SMS is the one channel
 * where a malformed number is silently accepted by the gateway, billed, and
 * never delivered — it fails at the handset, after the money is spent.
 */
public record SmsSendRequest(
        @NotNull SubjectType subjectType,
        @NotNull UUID subjectId,
        @Size(max = 80) String templateCode,
        @NotNull @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile")
        String mobile,
        @Size(max = 4) List<@Size(max = 300) String> bodyValues
) {

    public SendRequest toSendRequest(UUID actorId) {
        return SendRequest.sms(subjectType, subjectId, templateCode, mobile, bodyValues, actorId);
    }
}
