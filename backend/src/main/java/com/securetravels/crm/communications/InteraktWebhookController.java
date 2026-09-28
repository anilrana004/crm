package com.securetravels.crm.communications;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public Interakt webhook (Module 4).
 *
 * <p>Authenticates per request with {@code Interakt-Signature: sha256=<hex>},
 * an HMAC-SHA256 over the <strong>raw</strong> body. The body is taken as
 * {@code byte[]} for exactly that reason — re-serialising a parsed object
 * changes key order and whitespace and would invalidate every signature.
 *
 * <p>No {@code @PreAuthorize}: this path is covered by the existing
 * {@code /api/webhook/**} permit rule in {@code SecurityConfig} and carries its
 * own HMAC authentication.
 */
@RestController
@RequestMapping("/api/webhook/interakt")
public class InteraktWebhookController {

    public static final String SIGNATURE_HEADER = "Interakt-Signature";

    private final InteraktWebhookService service;

    public InteraktWebhookController(InteraktWebhookService service) {
        this.service = service;
    }

    @Operation(summary = "Interakt webhook (delivery status + inbound replies)",
            description = "Public endpoint for Interakt. Signature header " + SIGNATURE_HEADER
                    + ": sha256=<hex>, HMAC-SHA256 over the raw body. Acknowledges anything "
                    + "recognised, including duplicate deliveries, because Interakt retries non-2xx.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Event accepted (applied=0 means duplicate or unknown type)"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid HMAC signature")
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> receive(
            @RequestBody byte[] raw,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature) {
        int applied = service.handle(raw, signature);
        return Map.of("accepted", true, "applied", applied);
    }
}
