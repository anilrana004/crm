package com.securetravels.crm.communications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Interakt {@code POST /v1/public/message/} response.
 *
 * <p>There is <strong>no machine-readable error code</strong> — not on 200 and
 * not on 4xx. Interakt returns only {@code result} and a free-text
 * {@code message}, so the caller must branch on the HTTP status and treat
 * {@code message} as opaque. Modelling an {@code errorCode} here would be
 * inventing a contract that does not exist.
 *
 * <p>{@code message} is also not always a sentence: on a serializer validation
 * failure it contains a JSON-serialized array of errors, which is why it is
 * typed {@link String} and parsed defensively rather than as a list.
 *
 * <p>A 404 comes back as {@code text/html} (Django's debug page), not JSON — see
 * {@link InteraktWhatsAppGateway} for how that is handled.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteraktSendResponse(Boolean result, String message, String id) {

    public boolean succeeded() {
        return Boolean.TRUE.equals(result);
    }
}
