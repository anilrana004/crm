package com.securetravels.crm.communications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * An inbound SMS gateway webhook, normalised across providers
 * (Phase 5 Module 2).
 *
 * <p>MSG91 and other Indian gateways disagree on field names for the same
 * concept, so each logical field accepts several aliases and the service picks
 * the first non-blank. {@code @JsonIgnoreProperties} is essential rather than
 * cosmetic: a gateway adding a field must not turn an opt-out into a parse
 * failure, because a failed opt-out is a compliance failure.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SmsWebhookEvent(
        @JsonProperty("type") String type,
        @JsonProperty("id") String id,
        @JsonProperty("messageId") String messageId,
        @JsonProperty("mobile") String mobile,
        @JsonProperty("from") String from,
        @JsonProperty("phone") String phone,
        @JsonProperty("text") String text,
        @JsonProperty("body") String body,
        @JsonProperty("message") String message
) {
}
