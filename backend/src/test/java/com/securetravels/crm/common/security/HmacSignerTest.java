package com.securetravels.crm.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HMAC primitive shared by every inbound webhook (Module 4).
 *
 * <p>Worth pinning on its own: it is the only thing standing between a public
 * endpoint and forged events, and a regression here is silent — signatures would
 * simply start failing in production, after the endpoint is already exposed.
 */
class HmacSignerTest {

    private static final String SECRET = "test-interakt-webhook-secret";
    private static final byte[] BODY = "{\"type\":\"message_api_delivered\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("signs the raw bytes and round-trips")
    void roundTrip() {
        String signature = HmacSigner.sign(SECRET, BODY);
        assertThat(signature).startsWith("sha256=");
        assertThat(HmacSigner.matches(signature, SECRET, BODY)).isTrue();
    }

    @Test
    @DisplayName("matches the published HMAC-SHA256 test vector")
    void knownVector() {
        // RFC 4231 test case 1, so a future refactor cannot quietly change the
        // algorithm, the key handling, or the hex encoding.
        byte[] key = new byte[20];
        java.util.Arrays.fill(key, (byte) 0x0b);
        String expected = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7";
        assertThat(HmacSigner.computeHex(new String(key, StandardCharsets.ISO_8859_1),
                "Hi There".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(expected);
    }

    @Test
    @DisplayName("rejects a wrong secret, a mutated body, and a missing header")
    void rejectsTampering() {
        String signature = HmacSigner.sign(SECRET, BODY);

        assertThat(HmacSigner.matches(signature, "wrong-secret", BODY)).isFalse();
        assertThat(HmacSigner.matches(signature, SECRET,
                "{\"type\":\"message_api_failed\"}".getBytes(StandardCharsets.UTF_8))).isFalse();
        // One flipped hex digit.
        assertThat(HmacSigner.matches("sha256=" + flipLast(signature), SECRET, BODY)).isFalse();
        assertThat(HmacSigner.matches(null, SECRET, BODY)).isFalse();
        assertThat(HmacSigner.matches("   ", SECRET, BODY)).isFalse();
    }

    @Test
    @DisplayName("an empty body still signs deterministically")
    void emptyBody() {
        byte[] empty = new byte[0];
        assertThat(HmacSigner.sign(SECRET, empty)).isEqualTo(HmacSigner.sign(SECRET, empty));
        assertThat(HmacSigner.matches(HmacSigner.sign(SECRET, empty), SECRET, empty)).isTrue();
    }

    private static String flipLast(String signature) {
        char last = signature.charAt(signature.length() - 1);
        return signature.substring(0, signature.length() - 1) + (last == 'a' ? 'b' : 'a');
    }
}
