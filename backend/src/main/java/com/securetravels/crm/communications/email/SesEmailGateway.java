package com.securetravels.crm.communications.email;

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

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Live AWS SES v2 adapter (Phase 5 Module 2).
 *
 * <p>Selected by {@code app.email.mode=SES}. It talks the SES v2
 * {@code SendEmail} REST API directly and signs with {@link AwsSigV4Signer},
 * so the project keeps its "no cloud SDK" rule while still having a real
 * provider path.
 *
 * <p>Credentials and verified identities come from configuration. Until SES
 * DNS verification and a verified domain exist, this adapter cannot send — which
 * is why the default mode is the sandbox. See {@code docs/RUNBOOK_EMAIL.md}
 * for the DNS and IAM steps.
 */
@Component
@ConditionalOnProperty(prefix = "app.email", name = "mode", havingValue = "SES")
public class SesEmailGateway implements EmailGateway {

    private static final Logger log = LoggerFactory.getLogger(SesEmailGateway.class);
    public static final String PROVIDER = "SES";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final AwsSigV4Signer signer;
    private final String region;
    private final String endpoint;
    private final String fromAddress;
    private final String replyTo;

    public SesEmailGateway(RestClient.Builder builder, ObjectMapper objectMapper,
                           org.springframework.core.env.Environment env) {
        this.objectMapper = objectMapper;
        this.region = env.getProperty("app.email.region", "ap-south-1");
        this.fromAddress = env.getProperty("app.email.from", "bookings@securetravels.example");
        this.replyTo = env.getProperty("app.email.reply-to", fromAddress);
        String accessKey = env.getProperty("app.email.access-key");
        String secretKey = env.getProperty("app.email.secret-key");
        if (accessKey == null || secretKey == null) {
            throw new IllegalStateException(
                    "app.email.mode=SES requires app.email.access-key and app.email.secret-key");
        }
        this.signer = new AwsSigV4Signer(accessKey, secretKey, region, "ses");
        this.endpoint = env.getProperty("app.email.endpoint", "https://email." + region + ".amazonaws.com");
        this.restClient = builder.baseUrl(endpoint).build();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public SendResult send(SendCommand command) {
        try {
            Map<String, Object> content = new java.util.LinkedHashMap<>();
            Map<String, Object> simple = Map.of(
                    "subject", Map.of("data", command.subjectLine() == null ? "" : command.subjectLine(),
                            "charset", "UTF-8"),
                    "body", Map.of("text", Map.of("data", command.bodyText() == null ? "" : command.bodyText(),
                            "charset", "UTF-8")));
            content.put("simple", simple);

            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("FromEmailAddress", fromAddress);
            payload.put("Destination", Map.of("ToAddresses", List.of(command.toEmail())));
            if (command.replyTo() != null) {
                payload.put("ReplyToAddresses", List.of(command.replyTo()));
            }
            payload.put("Content", Map.of("Simple", content));

            byte[] body = objectMapper.writeValueAsBytes(payload);
            URI uri = URI.create(endpoint + "/v2/email/outbound-emails");
            Map<String, String> signed = signer.sign(
                    new AwsSigV4Signer.HttpRequest("POST", uri, body,
                            MediaType.APPLICATION_JSON_VALUE, Map.of()),
                    Instant.now());

            // exchange() rather than retrieve(): SES's whole error vocabulary is
            // HTTP-status shaped (429 throttle, 5xx transient, 4xx permanent), and
            // only exchange() hands back the status for the error cases too.
            ResponseEntity<String> response = restClient.post()
                    .uri("/v2/email/outbound-emails")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> signed.forEach(h::set))
                    .body(body)
                    .exchange((req, res) -> {
                        String raw = res.bodyTo(String.class);
                        return ResponseEntity.status(res.getStatusCode()).body(raw);
                    }, false);

            HttpStatusCode status = response.getStatusCode();
            if (!status.is2xxSuccessful()) {
                boolean retryable = status.value() == 429 || status.is5xxServerError();
                log.warn("[email][ses] HTTP {} to={} retryable={}", status.value(), command.toEmail(), retryable);
                return SendResult.failure("HTTP " + status.value(), String.valueOf(status.value()), retryable);
            }

            String messageId = objectMapper.readTree(response.getBody()).path("MessageId").asText(null);
            log.info("[email][ses] queued to={} messageId={}", command.toEmail(), messageId);
            return SendResult.ok(messageId);

        } catch (RestClientException e) {
            // Transport-level only: connection refused, DNS, read timeout. This
            // is the classic double-send case, so the intent row is what protects
            // the customer, not a retry.
            log.warn("[email][ses] transport failure to={}: {}", command.toEmail(), e.getMessage());
            return SendResult.failure(e.getMessage(), "transport error", true);
        } catch (Exception e) {
            log.error("[email][ses] unexpected failure to={}", command.toEmail(), e);
            return SendResult.failure(e.getMessage(), null, false);
        }
    }

    @Override
    public List<String> listTemplates() {
        // SES template management is an ops task (verified templates cannot be
        // created until the domain is verified); the local catalogue in
        // channel_templates stays the source of truth for what we may send.
        return List.of();
    }
}
