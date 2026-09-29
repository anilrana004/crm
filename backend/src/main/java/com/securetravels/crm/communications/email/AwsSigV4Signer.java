package com.securetravels.crm.communications.email;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * AWS Signature Version 4 for the SES v2 API (Phase 5 Module 2).
 *
 * <p>Written by hand because the project depends on no AWS SDK — the rule for
 * every provider here is "the rest of the system sees an interface, and the
 * HTTP call is the only place that knows the wire format". Adding
 * {@code software.amazon.awssdk:ses} later would let this class be deleted
 * without touching {@link EmailGateway} or anything above it.
 *
 * <p>Scope is exactly what SES v2 {@code SendEmail} needs: a POST with a JSON
 * body, no query string, and {@code x-amz-date} plus {@code host} among the
 * signed headers. Anything more (chunked signing, presigned URLs) is not
 * implemented on purpose.
 */
final class AwsSigV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "ses";
    private static final String TERMINATOR = "aws4_request";

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter SCOPE_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final String service;

    AwsSigV4Signer(String accessKeyId, String secretAccessKey, String region, String service) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region;
        this.service = service == null ? SERVICE : service;
    }

    /**
     * @return the headers to add to the request, including {@code Authorization}.
     */
    Map<String, String> sign(HttpRequest request, Instant signingTime) {
        String amzDate = AMZ_DATE.format(signingTime);
        String dateStamp = SCOPE_DATE.format(signingTime);
        String payloadHash = hex(sha256(request.body() == null ? new byte[0] : request.body()));

        String host = request.uri().getHost();
        // TreeMap: SigV4 requires headers sorted by lower-cased name.
        Map<String, String> headers = new TreeMap<>();
        headers.put("content-type", request.contentType());
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        request.additionalHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), v));

        StringBuilder canonicalHeaders = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : headers.entrySet()) {
            canonicalHeaders.append(header.getKey()).append(':')
                    .append(header.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(header.getKey());
        }

        // path is "" for a root-path request, which SigV4 requires literally.
        String path = request.uri().getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String canonicalQuery = canonicalQuery(request.uri());

        String canonicalRequest = request.method() + '\n'
                + path + '\n'
                + canonicalQuery + '\n'
                + canonicalHeaders + '\n'
                + signedHeaders + '\n'
                + payloadHash;

        String scope = dateStamp + '/' + region + '/' + service + '/' + TERMINATOR;
        String stringToSign = ALGORITHM + '\n'
                + amzDate + '\n'
                + scope + '\n'
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = signingKey(dateStamp);
        String signature = hex(hmacSha256(signingKey, stringToSign));

        String authorization = ALGORITHM
                + " Credential=" + accessKeyId + '/' + scope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        Map<String, String> out = new TreeMap<>();
        out.put("X-Amz-Date", amzDate);
        out.put("X-Amz-Content-Sha256", payloadHash);
        out.put("Authorization", authorization);
        return out;
    }

    private byte[] signingKey(String dateStamp) {
        byte[] kDate = hmacSha256(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmacSha256(kDate, region);
        byte[] kService = hmacSha256(kRegion, service);
        return hmacSha256(kService, TERMINATOR);
    }

    private static String canonicalQuery(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        // Sorted by encoded key then value, both RFC 3986 escaped.
        return java.util.Arrays.stream(query.split("&"))
                .map(pair -> {
                    int eq = pair.indexOf('=');
                    return eq < 0 ? pair : pair.substring(0, eq) + '=' + pair.substring(eq + 1);
                })
                .sorted()
                .reduce((a, b) -> a + '&' + b)
                .orElse("");
    }

    private static byte[] hmacSha256(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** The minimum request shape SigV4 needs. */
    record HttpRequest(String method, URI uri, byte[] body, String contentType,
                       Map<String, String> additionalHeaders) {
    }
}
