package com.securetravels.crm.communications.sms;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound SMS webhook endpoint (Phase 5 Module 2).
 *
 * <p>Answers 200 for anything understood — the provider retries non-2xx, and a
 * retried STOP is a customer still on a marketing list. A bad signature is the
 * deliberate exception: acknowledging a forged opt-out as accepted would be
 * worse than a retry.
 */
@RestController
@RequestMapping("/api/v1/webhooks/sms")
@Tag(name = "Webhooks")
public class SmsWebhookController {

    private final SmsWebhookService webhooks;

    public SmsWebhookController(SmsWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "Inbound SMS delivery report or customer reply (including STOP)")
    @PostMapping
    public ResponseEntity<Integer> inbound(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-SecureTravels-Signature", required = false) String signature) {
        return ResponseEntity.ok(webhooks.handle(rawBody, signature));
    }
}
