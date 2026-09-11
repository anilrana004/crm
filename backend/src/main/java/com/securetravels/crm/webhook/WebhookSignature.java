package com.securetravels.crm.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * HMAC-SHA256 request signing for the public webhook. Callers must send
 * {@code X-Webhook-Signature: sha256=<hex>} computed over the raw request
 * body with the shared secret. Comparison uses a constant-time digest.
 */
public final class WebhookSignature {

    private WebhookSignature() {}

    public static final String HEADER = "X-Webhook-Signature";

    public static String compute(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 not available", ex);
        }
    }

    public static boolean matches(String provided, String secret, byte[] payload) {
        if (provided == null) return false;
        byte[] expected = ("sha256=" + compute(secret, payload)).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}