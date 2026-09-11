package com.securetravels.crm.common.security;

import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.dto.ApiError;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bucket4j rate limiter keyed by client IP. Phase 1 applies it to:
 *   • POST /api/auth/login — 5 attempts / IP / 15 min (security requirement 5)
 *   • POST /api/webhook/lead — 20 requests / IP / min (security requirement 12)
 *
 * Buckets live in a bounded LRU map (per-IP entries are evicted after the cap)
 * so a hostile stream of distinct source IPs cannot grow memory without bound.
 */
@Component
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final int MAX_BUCKETS = 1024;

    private final Map<String, Bucket> buckets = java.util.Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                    return size() > MAX_BUCKETS;
                }
            });
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public RateLimitingFilter(AppProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** Which bucket applies to this request, keyed by type + IP. null = unlimited. */
    private String pathKey(HttpServletRequest request) {
        if (!request.getMethod().equalsIgnoreCase("POST")) return null;
        String uri = request.getRequestURI();
        if (uri.equals("/api/auth/login")) return "login";
        if (uri.equals("/api/webhook/lead")) return "webhook";
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = pathKey(request);
        if (key == null) {
            chain.doFilter(request, response);
            return;
        }

        String bucketKey = key + ":" + clientIp(request);
        boolean consumed;
        long waitSeconds;
        synchronized (buckets) {
            Bucket bucket = buckets.computeIfAbsent(bucketKey, k -> newBucket(key));
            var probe = bucket.tryConsumeAndReturnRemaining(1);
            consumed = probe.isConsumed();
            waitSeconds = probe.getNanosToWaitForRefill() / 1_000_000_000L;
        }
        if (!consumed) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(waitSeconds + 1));
            String message = "webhook".equals(key)
                    ? "Too many webhook requests. Try again later."
                    : "Too many login attempts. Try again later.";
            objectMapper.writeValue(response.getOutputStream(),
                    new ApiError(HttpStatus.TOO_MANY_REQUESTS.value(), "RATE_LIMITED", message));
            return;
        }
        chain.doFilter(request, response);
    }

    private Bucket newBucket(String key) {
        if ("webhook".equals(key)) {
            var limit = props.getWebhook().getRateLimit();
            return Bucket.builder().addLimit(Bandwidth.classic(limit.getCapacity(),
                    Refill.greedy(limit.getRefillPerWindow(), Duration.ofMinutes(limit.getWindowMinutes())))).build();
        }
        var limit = props.getLoginRateLimit();
        return Bucket.builder().addLimit(Bandwidth.classic(limit.getCapacity(),
                Refill.greedy(limit.getRefillPerWindow(), Duration.ofMinutes(limit.getWindowMinutes())))).build();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}