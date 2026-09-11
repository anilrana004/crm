package com.securetravels.crm.webhook;

import com.securetravels.crm.common.exception.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * Public, unauthenticated webhook (Module 9). Callers authenticate per
 * request with an HMAC-SHA256 signature over the raw body
 * ({@code X-Webhook-Signature: sha256=<hex>}), see WebhookSignature.
 * Rate-limited to 20 req/min/IP by RateLimitingFilter.
 *  201 = lead created (owner assigned, task + notification scheduled)
 *  200 = duplicate already exists (same success shape)
 *  400/401/503/429 = rejected
 */
@RestController
@RequestMapping("/api/webhook")
public class WebhookController {

    /** Website forms are tiny; anything bigger is abuse (bounds webhook_logs storage). */
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024;

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @Operation(summary = "Website lead intake (HMAC-signed)",
            description = "Public endpoint for the website enquiry form. Signature header "
                    + WebhookSignature.HEADER + ": sha256=<hex> computed over the raw body with the shared webhook secret.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Lead created; owner assigned; 5-min call task scheduled"),
            @ApiResponse(responseCode = "200", description = "Duplicate lead already active"),
            @ApiResponse(responseCode = "400", description = "Invalid/oversized payload, or missing consent"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid HMAC signature"),
            @ApiResponse(responseCode = "503", description = "No sales user available"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    })
    @PostMapping(path = "/lead", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<WebhookLeadResponse> create(
            @RequestBody byte[] raw,
            @RequestHeader(value = WebhookSignature.HEADER, required = false) String signature) {
        if (raw.length > MAX_PAYLOAD_BYTES) {
            throw new BadRequestException("Webhook payload exceeds " + MAX_PAYLOAD_BYTES + " bytes");
        }
        String payload = new String(raw, StandardCharsets.UTF_8);
        try {
            WebhookLeadResponse response = webhookService.ingest(raw, payload, signature);
            HttpStatus status = response.duplicate() ? HttpStatus.OK : HttpStatus.CREATED;
            return ResponseEntity.status(status).body(response);
        } catch (Exception ex) {
            webhookService.logFailure(payload, ex.getMessage());
            throw ex;
        }
    }
}