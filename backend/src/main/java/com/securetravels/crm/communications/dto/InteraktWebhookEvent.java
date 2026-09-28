package com.securetravels.crm.communications.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Interakt webhook envelope (Module 4).
 *
 * <p>Every field is optional/lenient on purpose. Interakt's own documentation
 * is internally inconsistent in places — notably {@code message_api_clicked},
 * where click fields appear both under {@code data.event} and directly on
 * {@code data.message}. Rather than guess, {@code @JsonIgnoreProperties} plus
 * nullable fields means an unexpected shape degrades to "we logged less detail"
 * instead of a 400 that makes Interakt retry forever.
 *
 * <p>Verified shape (see {@code docs/INTEGRATIONS.md}):
 * <ul>
 *   <li>{@code data.message.message} is a plain string for an inbound customer
 *       reply, but a <em>stringified JSON array</em> of content parts for an
 *       outbound message — hence {@link JsonNode}, not {@code String}.</li>
 *   <li>{@code data.message.meta_data.source_data.callback_data} is the
 *       correlation token we set at send time.</li>
 *   <li>{@code channel_error_code} is a string (e.g. {@code "1013"}).</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InteraktWebhookEvent(
        @JsonProperty("version") String version,
        @JsonProperty("timestamp") String timestamp,
        @JsonProperty("type") String type,
        @JsonProperty("data") Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Data(
            @JsonProperty("customer") Customer customer,
            @JsonProperty("message") Message message,
            @JsonProperty("event") Event event) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Customer(
            @JsonProperty("id") String id,
            @JsonProperty("phone_number") String phoneNumber,
            @JsonProperty("country_code") String countryCode,
            @JsonProperty("traits") Map<String, Object> traits) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            @JsonProperty("id") String id,
            @JsonProperty("chat_message_type") String chatMessageType,
            @JsonProperty("message_status") String messageStatus,
            @JsonProperty("channel_error_code") String channelErrorCode,
            @JsonProperty("channel_failure_reason") String channelFailureReason,
            @JsonProperty("is_template_message") Boolean templateMessage,
            @JsonProperty("message_content_type") String messageContentType,
            @JsonProperty("raw_template") String rawTemplate,
            @JsonProperty("media_url") String mediaUrl,
            @JsonProperty("message") JsonNode message,
            @JsonProperty("meta_data") MetaData metaData) {

        /**
         * Text of an inbound customer message, or {@code null} when the node is
         * an outbound content-parts array (not customer-authored text).
         */
        public String inboundText() {
            if (message == null || !message.isTextual()) return null;
            String text = message.asText();
            return text == null || text.isBlank() ? null : text;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MetaData(
            @JsonProperty("source") String source,
            @JsonProperty("source_data") SourceData sourceData) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceData(
            @JsonProperty("callback_data") String callbackData) {
    }

    /** Click details for {@code message_api_clicked}; Interakt places this inconsistently. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Event(
            @JsonProperty("click_type") String clickType,
            @JsonProperty("button_text") String buttonText,
            @JsonProperty("button_link") String buttonLink,
            @JsonProperty("click_timestamp") String clickTimestamp) {
    }
}
