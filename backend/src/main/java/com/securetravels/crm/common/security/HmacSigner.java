package com.securetravels.crm.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared HMAC-SHA256 signing/verification for inbound webhooks.
 *
 * <p>Extracted from {@code webhook.WebhookSignature} so the Interakt webhook
 * verifies signatures with exactly the same primitive instead of a second
 * copy of the crypto. One implementation, one thing to audit.
 *
 * <p>The wire format is {@code sha256=<lowercase hex>} over the
 * <strong>raw request body</strong> — not a re-serialised object. Re-serialising
 * changes key order and whitespace and would break every signature, which is
 * why controllers here accept {@code byte[]} rather than a parsed DTO.
 *
 * <p>Comparison is constant-time via {@link MessageDigest#isEqual}. A timing
 * side channel on a signature check is a real, if slow, attack.
 */
public final class HmacSigner {

    private HmacSigner() {
    }

    public static final String PREFIX = "sha256=";

    /** @return lowercase hex of HMAC-SHA256(secret, payload). */
    public static String computeHex(String secret, byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 not available", ex);
        }
    }

    /** @return {@code sha256=<hex>} for the given payload. */
    public static String sign(String secret, byte[] payload) {
        return PREFIX + computeHex(secret, payload);
    }

    public static boolean matches(String provided, String secret, byte[] payload) {
        if (provided == null || provided.isBlank()) return false;
        byte[] expected = sign(secret, payload).getBytes(StandardCharsets.UTF_8);
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
