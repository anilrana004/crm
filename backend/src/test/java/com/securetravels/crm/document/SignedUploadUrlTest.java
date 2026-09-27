package com.securetravels.crm.document;

import com.securetravels.crm.common.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SignedUploadUrlTest {

    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");
    private static final String KEY = "compliance/11111111-1111-1111-1111-111111111111/202609/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa-ID_PROOF";

    private AppProperties props;
    private SignedUploadUrlService signer;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        signer = new SignedUploadUrlService(props);
    }

    @Test
    void issuesSigV4PresignedPutUrlForTheConfiguredBucket() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        assertThat(url).startsWith(props.getStorage().getEndpoint() + "/" + props.getStorage().getBucket() + "/");
        assertThat(url).contains("X-Amz-Algorithm=AWS4-HMAC-SHA256");
        assertThat(url).contains("X-Amz-Credential=" + props.getStorage().getAccessKey() + "%2F");
        assertThat(url).contains("X-Amz-Date=20260911T000000Z");
        assertThat(url).contains("X-Amz-Expires=60");
        // Only `host` may be signed on a presigned PUT: every signed header must be
        // one the client actually sends, and browsers do not send
        // x-amz-content-sha256. Signing it made every real upload fail.
        assertThat(url).contains("X-Amz-SignedHeaders=host");
        assertThat(url).doesNotContain("x-amz-content-sha256");
        assertThat(url).contains("X-Amz-Signature=");
    }

    @Test
    void validUrlPassesWithinItsWindow() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        SignedUploadUrlService.VerifyResult result = signer.verify(url, "PUT", NOW.plusSeconds(30));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void expiredUrlIsRejected() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        SignedUploadUrlService.VerifyResult result = signer.verify(url, "PUT", NOW.plusSeconds(61));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("expired");
    }

    @Test
    void urlJustAtExpiryIsRejected() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        assertThat(signer.verify(url, "PUT", NOW.plusSeconds(60)).valid()).isFalse();
    }

    @Test
    void tamperedSignaturePayloadIsRejected() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));
        // Change a signed parameter (expiry window) — signature can no longer match.
        String tampered = url.replace("X-Amz-Expires=60", "X-Amz-Expires=600");

        SignedUploadUrlService.VerifyResult result = signer.verify(tampered, "PUT", NOW.plusSeconds(30));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("signature mismatch");
    }

    @Test
    void onlyPutIsAllowed() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        assertThat(signer.verify(url, "GET", NOW).valid()).isFalse();
        assertThat(signer.verify(url, "POST", NOW).valid()).isFalse();
    }

    @Test
    void urlForAnotherEndpointIsRejected() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));
        // Same signature params but a foreign host — the signature covers the host,
        // so this both fails the host check.
        String foreign = url.replace("http://127.0.0.1:9000/", "http://evil.example.com/");

        SignedUploadUrlService.VerifyResult result = signer.verify(foreign, "PUT", NOW);

        assertThat(result.valid()).isFalse();
    }

    /**
     * Regression guard for the bug that made every browser upload fail against a
     * live S3/MinIO endpoint while every other test in this class still passed:
     * {@link #issue} and {@link #verify} share one constant, so a wrong value
     * cancels out internally and stays invisible here. This asserts the wire
     * format explicitly instead.
     */
    @Test
    void presignedUrlSendsOnlyHeadersAClientWillActuallySend() {
        String url = signer.issue(KEY, NOW, Duration.ofSeconds(60));

        String signedHeaders = java.net.URLDecoder.decode(
                java.util.regex.Pattern.compile("X-Amz-SignedHeaders=([^&]+)").matcher(url).results()
                        .findFirst().orElseThrow().group(1),
                java.nio.charset.StandardCharsets.UTF_8);

        assertThat(signedHeaders).isEqualTo("host");
        assertThat(java.util.Arrays.stream(signedHeaders.split(";")).toList())
                .allMatch(h -> h.equals("host"));
    }
}