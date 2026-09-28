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
    private final Messaging messaging = new Messaging();
    private final WhatsApp whatsapp = new WhatsApp();
    private boolean bootstrapDemoData = true;

    public Jwt getJwt() { return jwt; }
    public Cors getCors() { return cors; }
    public LoginRateLimit getLoginRateLimit() { return loginRateLimit; }
    public Webhook getWebhook() { return webhook; }
    public Compliance getCompliance() { return compliance; }
    public Storage getStorage() { return storage; }
    public Capacity getCapacity() { return capacity; }
    public Messaging getMessaging() { return messaging; }
    public WhatsApp getWhatsApp() { return whatsapp; }
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

    /**
     * Module 4 — how outbound communication is delivered (ADR 0005).
     *
     * <p>{@link Mode#INLINE} calls the provider gateway on the calling thread
     * with the same retry/backoff the consumer would use, so the whole test
     * suite and local dev need no broker. {@link Mode#BROKER} publishes to
     * RabbitMQ and lets a single {@code @RabbitListener} consumer deliver.
     *
     * <p>The broker is never load-bearing: a publish that fails falls back to
     * inline delivery rather than dropping the message.
     */
    public static class Messaging {
        public enum Mode { INLINE, BROKER }

        private Mode mode = Mode.INLINE;
        private String exchange = "securetravels.communication";
        private String queue = "securetravels.whatsapp.dispatch";
        private String deadLetterQueue = "securetravels.whatsapp.dispatch.dlq";
        private String routingKey = "whatsapp.dispatch";
        /** Attempts before a message is dead-lettered. Total sends = maxAttempts. */
        private int maxAttempts = 3;
        /** Linear backoff unit: attempt N waits (N-1) * this. */
        private long retryBackoffMillis = 2_000;

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
        public String getExchange() { return exchange; }
        public void setExchange(String exchange) { this.exchange = exchange; }
        public String getQueue() { return queue; }
        public void setQueue(String queue) { this.queue = queue; }
        public String getDeadLetterQueue() { return deadLetterQueue; }
        public void setDeadLetterQueue(String deadLetterQueue) { this.deadLetterQueue = deadLetterQueue; }
        public String getRoutingKey() { return routingKey; }
        public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
        public long getRetryBackoffMillis() { return retryBackoffMillis; }
        public void setRetryBackoffMillis(long retryBackoffMillis) { this.retryBackoffMillis = retryBackoffMillis; }

        public boolean brokerEnabled() { return mode == Mode.BROKER; }
    }

    /**
     * Module 4 — Interakt (company-owned WhatsApp WABA).
     *
     * <p>{@link Mode#SANDBOX} is the default and is the reason this module is
     * buildable and testable without live credentials: it records the send and
     * returns a synthetic provider id. {@link Mode#INTERAKT} performs real HTTP.
     *
     * <p>See {@code docs/INTEGRATIONS.md} for the verified endpoint contract
     * and the fields that remain unconfirmed until a live key is available.
     */
    public static class WhatsApp {
        public enum Mode { SANDBOX, INTERAKT }

        private Mode mode = Mode.SANDBOX;
        private String baseUrl = "https://api.interakt.ai/v1/public";
        private String apiKey = "";
        private String webhookSecret = "dev-interakt-webhook-secret-change-me";
        private String countryCode = "+91";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration readTimeout = Duration.ofSeconds(10);
        /** Interakt plan quota (300/min Growth, 600/min Advanced). */
        private int rateLimitPerMinute = 300;

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getWebhookSecret() { return webhookSecret; }
        public void setWebhookSecret(String webhookSecret) { this.webhookSecret = webhookSecret; }
        public String getCountryCode() { return countryCode; }
        public void setCountryCode(String countryCode) { this.countryCode = countryCode; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
        public int getRateLimitPerMinute() { return rateLimitPerMinute; }
        public void setRateLimitPerMinute(int rateLimitPerMinute) { this.rateLimitPerMinute = rateLimitPerMinute; }

        public boolean live() { return mode == Mode.INTERAKT; }

        /**
         * Interakt's API key is already {@code base64(accessToken + ":")} as
         * pasted from the dashboard, so the header is a verbatim copy. Building
         * it from parts is the documented mistake — see
         * {@code InteraktWhatsAppGateway}.
         */
        public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
    }
}