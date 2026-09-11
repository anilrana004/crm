package com.securetravels.crm.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final LoginRateLimit loginRateLimit = new LoginRateLimit();
    private final Webhook webhook = new Webhook();
    private boolean bootstrapDemoData = true;

    public Jwt getJwt() { return jwt; }
    public Cors getCors() { return cors; }
    public LoginRateLimit getLoginRateLimit() { return loginRateLimit; }
    public Webhook getWebhook() { return webhook; }
    public boolean isBootstrapDemoData() { return bootstrapDemoData; }
    public void setBootstrapDemoData(boolean bootstrapDemoData) { this.bootstrapDemoData = bootstrapDemoData; }

    public static class Jwt {
        private String secret;
        private long accessMinutes = 15;
        private long refreshDays = 7;

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getAccessMinutes() { return accessMinutes; }
        public void setAccessMinutes(long accessMinutes) { this.accessMinutes = accessMinutes; }
        public long getRefreshDays() { return refreshDays; }
        public void setRefreshDays(long refreshDays) { this.refreshDays = refreshDays; }

        public Duration accessTtl() { return Duration.ofMinutes(accessMinutes); }
        public Duration refreshTtl() { return Duration.ofDays(refreshDays); }
    }

    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:3000");

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public static class LoginRateLimit {
        private long capacity = 5;
        private long refillPerWindow = 5;
        private long windowMinutes = 15;

        public long getCapacity() { return capacity; }
        public void setCapacity(long capacity) { this.capacity = capacity; }
        public long getRefillPerWindow() { return refillPerWindow; }
        public void setRefillPerWindow(long refillPerWindow) { this.refillPerWindow = refillPerWindow; }
        public long getWindowMinutes() { return windowMinutes; }
        public void setWindowMinutes(long windowMinutes) { this.windowMinutes = windowMinutes; }
    }

    /** Public webhook (Module 9): HMAC shared secret + per-IP intake limit. */
    public static class Webhook {
        private String secret = "dev-webhook-secret-insecure-change-me";
        private final RateLimit rateLimit = new RateLimit();

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public RateLimit getRateLimit() { return rateLimit; }

        public static class RateLimit {
            private long capacity = 20;
            private long refillPerWindow = 20;
            private long windowMinutes = 1;

            public long getCapacity() { return capacity; }
            public void setCapacity(long capacity) { this.capacity = capacity; }
            public long getRefillPerWindow() { return refillPerWindow; }
            public void setRefillPerWindow(long refillPerWindow) { this.refillPerWindow = refillPerWindow; }
            public long getWindowMinutes() { return windowMinutes; }
            public void setWindowMinutes(long windowMinutes) { this.windowMinutes = windowMinutes; }
        }
    }
}