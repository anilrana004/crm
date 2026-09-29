package com.securetravels.crm.communications.email;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SES delivery-notification endpoint (Phase 5 Module 2).
 *
 * <p>Always answers 200 for anything we understood. SNS retries any non-2xx, so
 * returning an error for an unrecognised notification type would retry forever.
 * A bad signature is the one deliberate exception — see
 * {@link EmailWebhookService#handle}.
 */
@RestController
@RequestMapping("/api/v1/webhooks/email")
@Tag(name = "Webhooks")
public class EmailWebhookController {

    private final EmailWebhookService webhooks;

    public EmailWebhookController(EmailWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @Operation(summary = "SES delivery / open / bounce / complaint notification")
    @PostMapping
    public ResponseEntity<Integer> ses(
            @RequestBody byte[] rawBody,
            @RequestHeader(value = "X-SecureTravels-Signature", required = false) String signature) {
        return ResponseEntity.ok(webhooks.handle(rawBody, signature));
    }
}
