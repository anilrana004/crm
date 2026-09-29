package com.securetravels.crm.communications;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Operator-initiated outbound email (Phase 5 Module 2).
 *
 * <p>Deliberately the same shape as {@code WhatsAppSendRequest} so one call site
 * can be written once per channel, and so the two channels cannot drift apart in
 * what they accept.
 *
 * <p>The recipient is required on the request even when a template is used,
 * because consent is looked up by address. Supplying a template and no address
 * would let a marketing template be sent without checking who may receive it.
 *
 * @param templateCode a cross-channel template code, or null to send bodyValues as free text
 * @param bodyValues   positional {@code {{1}}..{{4}} substitutions; free text is
 *                     joined with newlines when there is no template
 */
public record EmailSendRequest(
        @NotNull SubjectType subjectType,
        @NotNull UUID subjectId,
        @Size(max = 80) String templateCode,
        @NotNull @Email @Size(max = 255) String email,
        @Size(max = 4) List<@Size(max = 300) String> bodyValues
) {

    public SendRequest toSendRequest(UUID actorId) {
        return SendRequest.email(subjectType, subjectId, templateCode, email, bodyValues, actorId);
    }
}
