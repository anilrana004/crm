package com.securetravels.crm.communications.sms;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;

/**
 * Live MSG91 adapter (Phase 5 Module 2), selected by {@code app.sms.mode=MSG91}.
 *
 * <p>Uses the v5 {@code /5/flow} JSON API with an {@code X-Auth-Key} header.
 * The DLT fields are always sent — even when blank — because Indian operators
 * reject traffic without a registered template id, and sending them conditionally
 * turns a compliance problem into a runtime 400 instead of a visible gap.
 *
 * <p>Registration requires a DLT-registered sender id and, for most Indian
 * routes, a DLT template id per message. Both are configuration; see
 * {@code docs/RUNBOOK_SMS.md}.
 */
@Component
@ConditionalOnProperty(prefix = "app.sms", name = "mode", havingValue = "MSG91")
public class Msg91SmsGateway implements SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(Msg91SmsGateway.class);
    public static final String PROVIDER = "MSG91";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String authKey;
    private final String senderId;
    private final String endpoint;

    public Msg91SmsGateway(RestClient.Builder builder, ObjectMapper objectMapper,
                           org.springframework.core.env.Environment env) {
        this.objectMapper = objectMapper;
        this.authKey = env.getProperty("app.sms.msg91.auth-key");
        this.senderId = env.getProperty("app.sms.msg91.sender-id", "SECURE");
        this.endpoint = env.getProperty("app.sms.msg91.endpoint", "https://control.msg91.com");
        if (authKey == null || authKey.isBlank()) {
            throw new IllegalStateException("app.sms.mode=MSG91 requires app.sms.msg91.auth-key");
        }
        this.restClient = builder.baseUrl(endpoint).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        try {
            Map<String, Object> recipients = Map.of(
                    "mobiles", command.countryCode() + command.phoneNumber(),
                    "sms", command.body());

            Map<String, Object> dlt = Map.of(
                    "dltTemplateId", nullSafe(command.dltTemplateId()),
                    "senderId", nullSafe(command.senderId()));

            Map<String, Object> payload = Map.of(
                    "template_id", nullSafe(command.dltTemplateId()),
                    "short_url", "0",
                    "recipients", List.of(recipients),
                    "dlt", dlt,
                    "type", "transactional");

            // exchange() rather than retrieve(): MSG91 reports every failure as
            // an HTTP status, and a DLT rejection (4xx) must not be retried while
            // a throttle (429) must be.
            ResponseEntity<String> response = restClient.post()
                    .uri("/api/v5/flow/")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Auth-Key", authKey)
                    .body(objectMapper.writeValueAsBytes(payload))
                    .exchange((req, res) -> {
                        String raw = res.bodyTo(String.class);
                        return ResponseEntity.status(res.getStatusCode()).body(raw);
                    }, false);

            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
                boolean retryable = status.value() == 429 || status.is5xxServerError();
                log.warn("[sms][msg91] HTTP {} to={} retryable={}",
                        status.value(), command.phoneNumber(), retryable);
                return SendResult.failure("HTTP " + status.value(), String.valueOf(status.value()), retryable);
            }

            String messageId = objectMapper.readTree(response.getBody())
                    .path("data").path("message_id").asText(null);
            log.info("[sms][msg91] queued to={}{} messageId={}",
                    command.countryCode(), command.phoneNumber(), messageId);
            return SendResult.ok(messageId);

        } catch (RestClientException e) {
            // Transport-level: connect refused, DNS, read timeout.
            log.warn("[sms][msg91] transport failure to={}: {}", command.phoneNumber(), e.getMessage());
            return SendResult.failure(e.getMessage(), "transport error", true);
        } catch (Exception e) {
            log.error("[sms][msg91] unexpected failure to={}", command.phoneNumber(), e);
            return SendResult.failure(e.getMessage(), null, false);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
