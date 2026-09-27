package com.securetravels.crm.common.config;

import com.securetravels.crm.trip.Trip;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Jwt jwt = new Jwt();
    private final Cors cors = new Cors();
    private final LoginRateLimit loginRateLimit = new LoginRateLimit();
    private final Webhook webhook = new Webhook();
    private final Compliance compliance = new Compliance();
    private final Storage storage = new Storage();
    private final Capacity capacity = new Capacity();
    private boolean bootstrapDemoData = true;

    public Jwt getJwt() { return jwt; }
    public Cors getCors() { return cors; }
    public LoginRateLimit getLoginRateLimit() { return loginRateLimit; }
    public Webhook getWebhook() { return webhook; }
    public Compliance getCompliance() { return compliance; }
    public Storage getStorage() { return storage; }
    public Capacity getCapacity() { return capacity; }
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

    /** Module 1 compliance gate: threshold below which a batch cannot be
     *  marked READY_FOR_DEPARTURE, and the trip-difficulty at/above which a
     *  medical certificate is required for that trip. */
    public static class Compliance {
        private int readyThresholdPercent = 100;
        private Trip.Difficulty medicalThreshold = Trip.Difficulty.DIFFICULT;

        public int getReadyThresholdPercent() { return readyThresholdPercent; }
        public void setReadyThresholdPercent(int readyThresholdPercent) { this.readyThresholdPercent = readyThresholdPercent; }
        public Trip.Difficulty getMedicalThreshold() { return medicalThreshold; }
        public void setMedicalThreshold(Trip.Difficulty medicalThreshold) { this.medicalThreshold = medicalThreshold; }
    }

    /** S3-compatible object storage for documents (presigned PUT uploads only;
     *  file bytes never reach this application). Dev defaults match MinIO. */
    public static class Storage {
        private String endpoint = "http://127.0.0.1:9000";
        private String region = "us-east-1";
        private String bucket = "securetravels-documents";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private int presignedTtlSeconds = 300;

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public String getBucket() { return bucket; }
        public void setBucket(String bucket) { this.bucket = bucket; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public int getPresignedTtlSeconds() { return presignedTtlSeconds; }
        public void setPresignedTtlSeconds(int presignedTtlSeconds) { this.presignedTtlSeconds = presignedTtlSeconds; }
    }

    /**
     * Module 3 — batch capacity alerting.
     *
     * <p>{@code alertFillPercent} doubles as the AMBER colour cut-off, so the
     * dashboard colour and the "we warned ops" event can never disagree.
     * {@code minGroupSize}/{@code minGroupFillPercent}/{@code minGroupLeadDays}
     * describe the near-departure viability rule: a departure inside
     * {@code minGroupLeadDays} that has fewer than {@code minGroupSize} booked
     * travellers (or is below {@code minGroupFillPercent} of capacity) is
     * unlikely to run and needs a human decision.
     */
    public static class Capacity {
        private int alertFillPercent = 90;
        private int minGroupSize = 6;
        private int minGroupFillPercent = 50;
        private int minGroupLeadDays = 21;

        public int getAlertFillPercent() { return alertFillPercent; }
        public void setAlertFillPercent(int alertFillPercent) { this.alertFillPercent = alertFillPercent; }
        public int getMinGroupSize() { return minGroupSize; }
        public void setMinGroupSize(int minGroupSize) { this.minGroupSize = minGroupSize; }
        public int getMinGroupFillPercent() { return minGroupFillPercent; }
        public void setMinGroupFillPercent(int minGroupFillPercent) { this.minGroupFillPercent = minGroupFillPercent; }
        public int getMinGroupLeadDays() { return minGroupLeadDays; }
        public void setMinGroupLeadDays(int minGroupLeadDays) { this.minGroupLeadDays = minGroupLeadDays; }
    }
}