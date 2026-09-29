package com.securetravels.crm.communications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * SES delivery notification, as posted to the SNS topic we subscribe to
 * (Phase 5 Module 2).
 *
 * <p>Only the fields we act on are modelled. SES sends considerably more, and
 * {@code @JsonIgnoreProperties} keeps a provider-side addition from breaking
 * deserialization — an unmodelled field must never turn a delivery report into a
 * 500 and a retry storm.
 *
 * <p>The JSON keys are SNS's, not ours: {@code notificationType} and
 * {@code mail} are the SES payload nested inside the SNS envelope.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmailWebhookEvent(
        @JsonProperty("notificationType") String notificationType,
        @JsonProperty("mail") Mail mail,
        @JsonProperty("content") String content
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Mail(
            @JsonProperty("messageId") String messageId,
            @JsonProperty("timestamp") String timestamp,
            @JsonProperty("destination") java.util.List<String> destination,
            @JsonProperty("bounceType") String bounceType,
            @JsonProperty("bounceSubType") String bounceSubType,
            @JsonProperty("complaintFeedbackType") String complaintFeedbackType,
            @JsonProperty("source") String source,
            @JsonProperty("commonHeaders") CommonHeaders commonHeaders
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommonHeaders(
            @JsonProperty("from") java.util.List<String> from,
            @JsonProperty("to") java.util.List<String> to,
            @JsonProperty("subject") String subject
    ) {
    }
}
