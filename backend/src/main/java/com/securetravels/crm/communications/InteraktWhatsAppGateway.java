package com.securetravels.crm.communications;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.communications.dto.InteraktSendRequest;
import com.securetravels.crm.communications.dto.InteraktSendResponse;
import com.securetravels.crm.common.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * Live Interakt client (Module 4).
 *
 * <p>Three traps are handled here, each of which presents as a baffling
 * 404/401 in the wild:
 *
 * <ol>
 *   <li><b>The auth header is a verbatim copy.</b> The key pasted from the
 *       Interakt dashboard is already {@code base64(accessToken + ":")} — the
 *       trailing colon is part of the value. It must be sent as-is. Building
 *       it from a raw token, or using {@code HttpBasicCredentials}, emits
 *       {@code token:token} and fails with "Invalid token provided".</li>
 *   <li><b>The path ends in a slash.</b> {@code /v1/public/message} 404s;
 *       {@code /v1/public/message/} is the real route. This is the single most
 *       common cause of "Interakt returns 404" reports.</li>
 *   <li><b>Errors are not JSON on 404.</b> A 404 is Django's {@code text/html}
 *       debug page, so a blind {@code ObjectMapper} read turns a clear 404 into
 *       a confusing parse error.</li>
 * </ol>
 *
 * <p>The response is read via {@code exchange} rather than
 * {@code retrieve().body(...)} so the HTTP status is always available.
 * Interakt publishes no error code, so the status is the only reliable signal
 * for retryability.
 *
 * <p>No free-text send is implemented: Interakt's public API is template-only,
 * and free text outside the 24-hour service window is rejected anyway.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        prefix = "app.whatsapp", name = "mode", havingValue = "INTERAKT")
public class InteraktWhatsAppGateway implements WhatsAppGateway {

    private static final Logger log = LoggerFactory.getLogger(InteraktWhatsAppGateway.class);
    public static final String PROVIDER = "INTERAKT";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    public InteraktWhatsAppGateway(RestClient interaktRestClient, ObjectMapper objectMapper,
                                   AppProperties props) {
        this.restClient = interaktRestClient;
        this.objectMapper = objectMapper;
        this.baseUrl = trimTrailingSlash(props.getWhatsApp().getBaseUrl());
        this.apiKey = props.getWhatsApp().getApiKey();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        if (apiKey == null || apiKey.isBlank()) {
            // Misconfiguration, not a provider fault: failing loudly here beats
            // spending a DLQ slot on a permanent auth rejection.
            return SendResult.failure("INTERAKT_API_KEY is not set", null, null, false);
        }

        InteraktSendRequest body = InteraktSendRequest.template(
                command.countryCode(), command.phoneNumber(), command.templateName(),
                command.languageCode(), nullSafe(command.bodyValues()), command.callbackData());

        ResponseEntity<String> response;
        try {
            // exchange() rather than retrieve(): it hands back the status and
            // body for 4xx/5xx too, with no automatic error handling to reason
            // about. Interakt's whole error vocabulary is HTTP-status shaped, so
            // the status must be the thing we branch on.
            response = restClient.post()
                    .uri(baseUrl + "/message/")
                    .contentType(MediaType.APPLICATION_JSON)
                    // Verbatim, not reconstructed — see class Javadoc.
                    .header("Authorization", "Basic " + apiKey)
                    .body(body)
                    .exchange((req, res) -> {
                        String raw = res.bodyTo(String.class);
                        return ResponseEntity.status(res.getStatusCode()).body(raw);
                    }, false);
        } catch (RestClientException e) {
            // Transport-level only (connection refused, DNS, read timeout).
            log.warn("[interakt] transport failure for template={}: {}", command.templateName(), e.getMessage());
            return SendResult.failure(shorten(e.getMessage(), 500), null, null, true);
        }

        return interpret(response.getStatusCode(), response.getBody());
    }

    private SendResult interpret(HttpStatusCode status, String raw) {
        if (!status.is2xxSuccessful()) {
            // 429 and 5xx are transient; other 4xx are the caller's fault
            // (unregistered recipient, bad template, plan gate) and must not be
            // retried into a DLQ.
            boolean retryable = status.value() == 429 || status.is5xxServerError();
            String detail = extractMessage(raw);
            log.warn("[interakt] HTTP {} sending template: {}", status.value(), detail);
            return SendResult.failure(shorten("HTTP " + status.value() + ": " + detail, 500),
                    String.valueOf(status.value()), shorten(detail, 300), retryable);
        }

        if (raw == null || raw.isBlank()) {
            return SendResult.failure("empty response from Interakt", null, null, true);
        }

        InteraktSendResponse parsed;
        try {
            parsed = objectMapper.readValue(raw, InteraktSendResponse.class);
        } catch (Exception e) {
            return SendResult.failure("non-JSON response from Interakt: " + shorten(stripMarkup(raw), 300),
                    null, null, true);
        }

        if (parsed.succeeded()) {
            if (parsed.id() == null) {
                // result:true with no id: accepted but uncorrelatable. Recorded
                // as sent rather than retried, because resending risks a
                // duplicate message to a real customer.
                log.warn("[interakt] result=true but no message id; status webhooks cannot be correlated");
            }
            return SendResult.ok(parsed.id());
        }

        String message = parsed.message() == null ? "rejected by Interakt" : parsed.message();
        boolean retryable = isTransientMessage(message);
        return SendResult.failure(shorten(message, 500), null, shorten(message, 300), retryable);
    }

    /**
     * Pull the useful text out of an error body. On 400 the {@code message}
     * field itself contains a JSON-serialized array of serializer errors, so
     * this stays deliberately crude rather than trying to be clever about a
     * shape Interakt does not contract.
     */
    private String extractMessage(String raw) {
        if (raw == null || raw.isBlank()) return "no response body";
        try {
            InteraktSendResponse parsed = objectMapper.readValue(raw, InteraktSendResponse.class);
            if (parsed.message() != null) return parsed.message();
            if (parsed.result() != null) return "result=" + parsed.result();
        } catch (Exception ignored) {
            // Fall through to the raw body (e.g. Django's HTML 404 page).
        }
        return stripMarkup(raw);
    }

    private static boolean isTransientMessage(String message) {
        String m = message == null ? "" : message.toLowerCase();
        return m.contains("rate limit")
                || m.contains("429")
                || m.contains("service window")
                || m.contains("temporarily");
    }

    private static String stripMarkup(String html) {
        return html.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String shorten(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }
}
