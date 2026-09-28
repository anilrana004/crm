package com.securetravels.crm.webhook;

import com.securetravels.crm.common.security.HmacSigner;

/**
 * HMAC-SHA256 request signing for the public webhook. Callers must send
 * {@code X-Webhook-Signature: sha256=<hex>} computed over the raw request
 * body with the shared secret. Comparison uses a constant-time digest.
 *
 * <p>The primitive itself now lives in {@code common.security.HmacSigner},
 * shared with the Interakt webhook (Module 4) — this type remains the
 * lead-intake-specific header name and facade.
 */
public final class WebhookSignature {

    private WebhookSignature() {}

    public static final String HEADER = "X-Webhook-Signature";

    public static String compute(String secret, byte[] payload) {
        return HmacSigner.computeHex(secret, payload);
    }

    public static boolean matches(String provided, String secret, byte[] payload) {
        return HmacSigner.matches(provided, secret, payload);
    }
}
