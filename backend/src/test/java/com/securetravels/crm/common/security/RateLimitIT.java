package com.securetravels.crm.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.webhook.WebhookSignature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the RateLimitingFilter actually throttles (not just is "planned"):
 * runs under its own Spring context with test-realistic limits
 * (5 logins / 15 min, 3 webhooks / min) that the shared BASE test profile
 * deliberately widens to 10k so the main suite is never flaky. Each request
 * uses a unique X-Forwarded-For so no bucket escapes this class.
 */
@TestPropertySource(properties = {
        "app.login-rate-limit.capacity=5",
        "app.login-rate-limit.refill-per-window=5",
        "app.login-rate-limit.window-minutes=15",
        "app.webhook.rate-limit.capacity=3",
        "app.webhook.rate-limit.refill-per-window=3",
        "app.webhook.rate-limit.window-minutes=1"
})
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AppProperties props;

    @Test
    void loginAllowsFiveThenReturns429() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            statuses.add(mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", LOGIN_IP)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "email", "nobody@securetravels.in", "password", "wrong"))))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(statuses.subList(0, 5)).doesNotContain(429);
        assertThat(statuses.get(5)).isEqualTo(429);
    }

    @Test
    void login429HasRateLimitCodeAndRetryAfter() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", LOGIN_IP_2)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"a@b.in\",\"password\":\"x\"}"))
                    .andReturn().getResponse().getStatus();
        }
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", LOGIN_IP_2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"a@b.in\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void webhookAllowsThreePerMinuteThenReturns429() throws Exception {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Map<String, Object> body = webhookBody();
            statuses.add(mockMvc.perform(post("/api/webhook/lead")
                            .header("X-Forwarded-For", WEBHOOK_IP)
                            .header(WebhookSignature.HEADER, signature(body))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andReturn().getResponse().getStatus());
        }
        assertThat(statuses.subList(0, 3)).doesNotContain(429);
        assertThat(statuses.get(3)).isEqualTo(429);
    }

    private Map<String, Object> webhookBody() {
        return new java.util.LinkedHashMap<>(Map.of(
                "customer_name", "Rate Test",
                "mobile_number", "98" + (10000000 + (int) (Math.random() * 89999999)),
                "destination", "Rishikesh",
                "num_persons", 1,
                "consent_given", true));
    }

    private String signature(Map<String, Object> body) throws Exception {
        return "sha256=" + WebhookSignature.compute(props.getWebhook().getSecret(),
                objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8));
    }

    private String fakeIp() {
        return "rate-test-" + UUID.randomUUID();
    }

    private static final String LOGIN_IP = "rate-test-login-" + UUID.randomUUID();
    private static final String LOGIN_IP_2 = "rate-test-login2-" + UUID.randomUUID();
    private static final String WEBHOOK_IP = "rate-test-webhook-" + UUID.randomUUID();
}