package com.securetravels.crm.document;

import com.securetravels.crm.common.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Issues AWS Signature Version 4 presigned PUT URLs against any S3-compatible
 * endpoint (MinIO/R2/OSS/...). File bytes go straight from the browser to
 * the bucket; this application only ever stores the object key.
 *
 * {@link #verify} recomputes the signature and enforces the expiry window —
 * the same guarantees object storage enforces at upload time — which lets us
 * prove expired-URL rejection without a live bucket.
 */
@Service
public class SignedUploadUrlService {

    private static final HexFormat HEX = HexFormat.of();
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE_PARSE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private static final String ALGO = "AWS4-HMAC-SHA256";
    private static final String SERVICE = "s3";
    private static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    /**
     * Presigned PUTs sign the {@code host} header only.
     *
     * <p>This is the standard SigV4 presigning rule and it is load-bearing: every
     * header named here must be sent by the client on the subsequent PUT. A
     * browser upload ({@code fetch}/{@code XMLHttpRequest}) cannot be made to send
     * {@code x-amz-content-sha256}, so naming it here caused S3/MinIO to reject
     * <em>every</em> real upload with
     * {@code AccessDenied - there were headers present in the request which were
     * not signed}. The payload hash is still part of the canonical request (as
     * {@link #UNSIGNED_PAYLOAD}); it is simply not a signed header.
     */
    private static final String SIGNED_HEADERS = "host";

    private final AppProperties props;

    public SignedUploadUrlService(AppProperties props) {
        this.props = props;
    }

    /** Molecular self-check result: valid + reason for a rejection. */
    public record VerifyResult(boolean valid, String reason) {
        static VerifyResult ok() { return new VerifyResult(true, "valid"); }
        static VerifyResult invalid(String reason) { return new VerifyResult(false, reason); }
    }

    /** Presigned PUT URL for the key, using the configured TTL. */
    public String issue(String objectKey, Instant now) {
        return issue(objectKey, now, Duration.ofSeconds(props.getStorage().getPresignedTtlSeconds()));
    }

    /** Presigned PUT URL with an explicit TTL (tests control the clock). */
    public String issue(String objectKey, Instant now, Duration ttl) {
        AppProperties.Storage s = props.getStorage();
        String amzDate = AMZ_DATE.format(now);
        String dateStamp = AMZ_DATE_STAMP.format(now);
        String scope = dateStamp + "/" + s.getRegion() + "/" + SERVICE + "/aws4_request";

        Map<String, String> params = new TreeMap<>();
        params.put("X-Amz-Algorithm", ALGO);
        params.put("X-Amz-Credential", s.getAccessKey() + "/" + scope);
        params.put("X-Amz-Date", amzDate);
        params.put("X-Amz-Expires", String.valueOf(ttl.getSeconds()));
        params.put("X-Amz-SignedHeaders", SIGNED_HEADERS);

        String canonicalUri = canonicalUri(s.getBucket(), objectKey);
        String canonicalQueryString = canonicalQueryString(params);
        String signature = signature(amzDate, scope, "PUT", canonicalUri, canonicalQueryString,
                canonicalHost(s.getEndpoint()), null);

        return s.getEndpoint() + canonicalUri + "?" + canonicalQueryString
                + "&X-Amz-Signature=" + signature;
    }

    /** Recomputed expiry instant for a URL issued at {@code now}. */
    public Instant expiresAt(Instant now) {
        return now.plusSeconds(props.getStorage().getPresignedTtlSeconds());
    }

    /**
     * Verifies a presigned URL: correct endpoint, PUT-only, matching
     * signature, and the expiry window still open at {@code now}.
     */
    public VerifyResult verify(String url, String method, Instant now) {
        try {
            AppProperties.Storage s = props.getStorage();
            URI u = URI.create(url);

            String vHost = canonicalHostFromUri(u);
            if (!vHost.equalsIgnoreCase(canonicalHost(s.getEndpoint()))) {
                return VerifyResult.invalid("endpoint host mismatch");
            }
            if (!u.getRawPath().startsWith("/" + s.getBucket() + "/")) {
                return VerifyResult.invalid("bucket mismatch");
            }
            if (!"PUT".equals(method)) {
                return VerifyResult.invalid("method must be PUT");
            }

            Map<String, String> params = parseQuery(u.getRawQuery());
            String algo = params.get("X-Amz-Algorithm");
            String credential = params.get("X-Amz-Credential");
            String amzDate = params.get("X-Amz-Date");
            String expires = params.get("X-Amz-Expires");
            String signedHeaders = params.get("X-Amz-SignedHeaders");
            String providedSignature = params.get("X-Amz-Signature");
            if (algo == null || credential == null || amzDate == null || expires == null
                    || signedHeaders == null || providedSignature == null) {
                return VerifyResult.invalid("missing sigv4 parameters");
            }
            if (!ALGO.equals(algo) || !SIGNED_HEADERS.equals(signedHeaders)) {
                return VerifyResult.invalid("unsupported algorithm/signed headers");
            }
            if (!credential.startsWith(s.getAccessKey() + "/")) {
                return VerifyResult.invalid("access key mismatch");
            }

            Map<String, String> canonicalParams = new TreeMap<>(params);
            canonicalParams.remove("X-Amz-Signature");
            String canonicalQueryString = canonicalQueryString(canonicalParams);
            String scope = credential.substring(credential.indexOf('/') + 1);
            String recomputed = signature(amzDate, scope, "PUT", u.getRawPath(),
                    canonicalQueryString, vHost, null);
            if (!MessageDigest.isEqual(recomputed.getBytes(StandardCharsets.US_ASCII),
                    providedSignature.toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
                return VerifyResult.invalid("signature mismatch");
            }

            Instant issued = LocalDateTime.parse(amzDate, AMZ_DATE_PARSE).toInstant(ZoneOffset.UTC);
            Instant expiry = issued.plusSeconds(Long.parseLong(expires));
            if (!expiry.isAfter(now)) {
                return VerifyResult.invalid("url expired at " + expiry);
            }
            return VerifyResult.ok();
        } catch (Exception e) {
            return VerifyResult.invalid("malformed url: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sigv4 internals

    private String signature(String amzDate, String scope, String method, String canonicalUri,
                             String canonicalQueryString, String host, String secretOverride) {
        String canonicalHeaders = "host:" + host + "\n";
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQueryString
                + "\n" + canonicalHeaders + "\n" + SIGNED_HEADERS + "\n" + UNSIGNED_PAYLOAD;

        String stringToSign = ALGO + "\n" + amzDate + "\n" + scope
                + "\n" + sha256Hex(canonicalRequest);

        String secret = secretOverride == null ? props.getStorage().getSecretKey() : secretOverride;
        String[] scopeParts = scope.split("/");
        byte[] kSigning = signingKey(secret, scopeParts[0], scopeParts[1]);
        return HexFormat.of().formatHex(hmac(kSigning, stringToSign));
    }

    private static byte[] signingKey(String secret, String dateStamp, String region) {
        byte[] kDate = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, SERVICE);
        return hmac(kService, "aws4_request");
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String sha256Hex(String value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Path-style canonical URI: {@code /<bucket>/<key>}, segments encoded. */
    static String canonicalUri(String bucket, String objectKey) {
        return "/" + uriEncode(bucket, false) + "/" + uriEncode(objectKey, false);
    }

    /** Canonical query string from a param map: sorted, RFC-3986 encoded. */
    static String canonicalQueryString(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        params.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(uriEncode(k, true)).append('=').append(uriEncode(v, true));
        });
        return sb.toString();
    }

    /** RFC 3986 uri encoding; {@code encodeSlash} distinguishes path/query. */
    static String uriEncode(String value, boolean encodeSlash) {
        StringBuilder sb = new StringBuilder();
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) b;
            if (isUnreserved(c)) {
                sb.append(c);
            } else if (c == '/' && !encodeSlash) {
                sb.append('/');
            } else {
                sb.append('%').append(String.format("%02X", b));
            }
        }
        return sb.toString();
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }

    /** Canonical host for the endpoint (includes non-default port). */
    private static String canonicalHost(String endpoint) {
        return canonicalHostFromUri(URI.create(endpoint));
    }

    private static String canonicalHostFromUri(URI u) {
        String host = u.getHost();
        if (host == null) return "";
        int port = u.getPort();
        boolean defaultPort = ("http".equals(u.getScheme()) && port == 80)
                || ("https".equals(u.getScheme()) && port == 443);
        return port == -1 || defaultPort ? host.toLowerCase() : host.toLowerCase() + ":" + port;
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> map = new TreeMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return map;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                map.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
            } else {
                map.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return map;
    }
}