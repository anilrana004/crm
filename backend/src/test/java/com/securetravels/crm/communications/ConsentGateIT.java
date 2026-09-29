package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.common.security.HmacSigner;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5 Module 1, end to end: consent is not a flag on a form, it is a gate
 * every outbound path stands in front of. A customer who has not GRANTED
 * marketing consent receives no marketing template; a STOP reply revokes it for
 * good; transactional messages go through regardless.
 */
class ConsentGateIT extends BaseIT {

    private static final String WEBHOOK_SECRET = "test-interakt-webhook-secret";
    private static final String MOBILE = "9876500088";

    @Autowired private WhatsAppMessageRepository messages;
    @Autowired private TimelineEventRepository timeline;
    @Autowired private Customer360Repository customers;

    // ------------------------------------------------------------------ send gate

    @Test
    void customerWithoutConsentCannotReceiveMarketingPlacedOnAWhatsappSendPath() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "POST_TRIP_REVIEW",
                                List.of("Asha Rao", "TOH-2026-0001", "2026-11-02"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Marketing WHATSAPP blocked")));

        // Zero marketing messages reached the channel...
        assertThat(messages.findAll()).isEmpty();
        // ...but the blocked attempt is recorded, not invisible.
        assertThat(timeline.findAll().stream()
                .filter(e -> e.getKind() == TimelineEvent.Kind.SYSTEM_NOTE)
                .anyMatch(e -> e.getSummary().contains("Marketing WHATSAPP blocked")))
                .isTrue();
    }

    @Test
    void grantedConsentLetsTheMarketingTemplateThrough() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/consent")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(customerId, "WHATSAPP", "GRANTED", "in-person enquiry form")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingStatus.WHATSAPP").value("GRANTED"));

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "POST_TRIP_REVIEW",
                                List.of("Asha Rao", "TOH-2026-0001", "2026-11-02"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.templateCode").value("POST_TRIP_REVIEW"));

        assertThat(timeline.findAll().stream()
                .filter(e -> e.getKind() == TimelineEvent.Kind.SYSTEM_NOTE)
                .anyMatch(e -> e.getSummary().contains("blocked")))
                .isFalse();
    }

    @Test
    void revokingConsentBlocksMarketingAgain() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        postConsent(manager, customerId, "GRANTED");
        postConsent(manager, customerId, "REVOKED");

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "POST_TRIP_REVIEW",
                                List.of("Asha Rao", "TOH-2026-0001", "2026-11-02"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void transactionalTemplateIsNotBlockedByMissingConsent() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "BOOKING_CONFIRMED",
                                List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))))
                .andExpect(status().isAccepted());
    }

    @Test
    void consentEndpointShowsUnknownUntilRecorded() throws Exception {
        String manager = managerToken();
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(get("/api/consent")
                        .header("Authorization", authHeader(manager))
                        .param("customerId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingStatus.WHATSAPP").value("UNKNOWN"))
                .andExpect(jsonPath("$.marketingStatus.EMAIL").value("UNKNOWN"))
                .andExpect(jsonPath("$.marketingStatus.SMS").value("UNKNOWN"));
    }

    // ------------------------------------------------------------------ opt-out

    @Test
    void aStopReplyRevokesWhatsAppMarketingAndBlocksSubsequentSends() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        // Give the customer a recent outbound message so the inbound resolves
        // to their conversation.
        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "BOOKING_CONFIRMED",
                                List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))))
                .andExpect(status().isAccepted());

        String reply = """
                {"type":"message_received","data":{
                  "customer":{"id":"c1","phone_number":"%s","country_code":"91"},
                  "message":{"id":"optout-reply-1","chat_message_type":"TEXT",
                             "message":"STOP"}}}""".formatted(MOBILE);
        webhook(reply).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

        mockMvc.perform(get("/api/consent")
                        .header("Authorization", authHeader(manager))
                        .param("customerId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketingStatus.WHATSAPP").value("REVOKED"));

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "POST_TRIP_REVIEW",
                                List.of("Asha Rao", "TOH-2026-0001", "2026-11-02"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aStopFromAGreetingMessageIsStillAnOptOut() throws Exception {
        // No prior outbound message: the number may not even belong to a
        // customer yet. The consent service must still process the opt-out.
        String reply = """
                {"type":"message_received","data":{
                  "customer":{"id":"c7","phone_number":"9000000000","country_code":"91"},
                  "message":{"id":"optout-reply-2","message":"STOP"}}}""";
        webhook(reply).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
    }

    // ------------------------------------------------------------------ helpers

    private String postConsent(String token, String customerId, String status) throws Exception {
        return mockMvc.perform(post("/api/consent")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(consentBody(customerId, "WHATSAPP", status, "IT")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String consentBody(String customerId, String channel, String status, String evidence)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("channel", channel);
        body.put("status", status);
        body.put("evidence", evidence);
        return objectMapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.ResultActions webhook(String body) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return mockMvc.perform(post("/api/webhook/interakt")
                .contentType(MediaType.APPLICATION_JSON)
                .header(InteraktWebhookController.SIGNATURE_HEADER, HmacSigner.sign(WEBHOOK_SECRET, raw))
                .content(raw));
    }

    private String sendBody(String subjectId, String templateCode, List<String> values) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectType", "CUSTOMER");
        body.put("subjectId", subjectId);
        body.put("templateCode", templateCode);
        body.put("mobile", MOBILE);
        body.put("bodyValues", values);
        return objectMapper.writeValueAsString(body);
    }

    private String salesToken(String name, String email) throws Exception {
        createUser(email, name, Role.SALES, "sales123");
        return login(email, "sales123");
    }

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String newCustomer(String phone) {
        return customers.save(Customer360.fromLead("Asha Rao", phone, phone, phone,
                "asha" + phone + "@example.com", true, "Consent gate IT")).getId().toString();
    }
}