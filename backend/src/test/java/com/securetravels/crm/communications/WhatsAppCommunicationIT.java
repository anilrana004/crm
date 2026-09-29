package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.common.security.HmacSigner;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.communications.inbound.InboundMessage;
import com.securetravels.crm.communications.inbound.InboundMessageRepository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 4 end-to-end: an operator-initiated send, the Interakt webhook
 * lifecycle, and the ownership boundary around customer communications.
 *
 * <p>Runs in INLINE mode against the sandbox gateway, so the assertions are about
 * our own guarantees — the state machine, the timeline, idempotency, and access
 * control — not about Interakt being reachable. Interakt's own contract is pinned
 * separately in {@code InteraktWhatsAppGatewayTest}.
 */
class WhatsAppCommunicationIT extends BaseIT {

    private static final String WEBHOOK_SECRET = "test-interakt-webhook-secret";
    private static final String MOBILE = "9876500000";

    @Autowired private WhatsAppMessageRepository messages;
    @Autowired private TimelineEventRepository timeline;
    @Autowired private Customer360Repository customerRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private InboundMessageRepository inboundMessages;
    @Autowired private BatchRepository batchRepository;

    // ------------------------------------------------------------------ templates

@Test
    void templatesSeededFromTheMigrationsAndVisibleToOperators() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");

        mockMvc.perform(get("/api/whatsapp/templates").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                // V12 seeded nine Module 4 templates; V14 added OPTOUT_CONFIRMED.
                .andExpect(jsonPath("$.length()").value(10))
                // The Interakt dashboard code name is what an operator must create.
                .andExpect(jsonPath("$[?(@.code=='BOOKING_CONFIRMED')].interaktName")
                        .value("securetravels_booking_confirmed"))
                .andExpect(jsonPath("$[?(@.code=='BOOKING_CONFIRMED')].expectedParams").value(4));
    }

    // ------------------------------------------------------------------ send

    @Test
    void operatorSendIsRecordedDeliveredAndTrackedOnTheTimeline() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "BOOKING_CONFIRMED",
                                List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.templateCode").value("BOOKING_CONFIRMED"))
                .andExpect(jsonPath("$.recipientMobile").value(MOBILE));

        WhatsAppMessage sent = messages.findAll().get(0);
        // INLINE mode delivers synchronously, so the row has already moved on.
        assertThat(sent.getStatus()).isEqualTo(WhatsAppMessage.Status.SENT);
        assertThat(sent.getAttempts()).isEqualTo(1);
        assertThat(sent.getSentAt()).isNotNull();

        // Both the intent and the outcome are on the customer timeline.
        List<TimelineEvent.Kind> kinds = kindsFor(customerId);
        assertThat(kinds).contains(TimelineEvent.Kind.TEMPLATE_QUEUED, TimelineEvent.Kind.TEMPLATE_SENT);

        // ...and the operator can read the message back, scoped to the subject.
        mockMvc.perform(get("/api/whatsapp/messages")
                        .header("Authorization", authHeader(token))
                        .param("subjectType", "CUSTOMER")
                        .param("subjectId", customerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("SENT"))
                .andExpect(jsonPath("$[0].attempts").value(1));
    }

    @Test
    void wrongParameterCountIsRejectedBeforeAnyProviderQuotaIsSpent() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "BOOKING_CONFIRMED", List.of("only one"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("expects 4 body value(s)")));

        assertThat(messages.findAll()).isEmpty();
    }

    @Test
    void invalidMobileIsRejected() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId, "BOOKING_CONFIRMED",
                                List.of("Asha", "TOH-1", "Manali", "2026-11-02")).replace(MOBILE, "12345")))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ webhook

    @Test
    void webhookWithAnInvalidSignatureIsRejected() throws Exception {
        mockMvc.perform(post("/api/webhook/interakt")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(InteraktWebhookController.SIGNATURE_HEADER, "sha256=deadbeef")
                        .content("{\"type\":\"message_api_delivered\",\"data\":{\"message\":{\"id\":\"x\"}}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deliveryStatusAdvancesTheMessageAndIsIdempotent() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);
        sendBookingConfirmed(token, customerId);

        String delivered = statusWebhook("message_api_delivered", "");
        webhook(delivered).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(1));

        // Interakt retries on timeout and re-delivers after a restart. A second
        // delivery must not append a second timeline row.
        webhook(delivered).andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));

        assertThat(messages.findAll().get(0).getStatus()).isEqualTo(WhatsAppMessage.Status.DELIVERED);
        assertThat(kindsFor(customerId))
                .containsOnlyOnce(TimelineEvent.Kind.TEMPLATE_DELIVERED);
    }

    @Test
    void readStatusIsRecordedAfterDelivery() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);
        sendBookingConfirmed(token, customerId);

        webhook(statusWebhook("message_api_delivered", "")).andExpect(jsonPath("$.applied").value(1));
        webhook(statusWebhook("message_api_read", "")).andExpect(jsonPath("$.applied").value(1));

        assertThat(messages.findAll().get(0).getStatus()).isEqualTo(WhatsAppMessage.Status.READ);
        assertThat(kindsFor(customerId)).contains(TimelineEvent.Kind.TEMPLATE_READ);
    }

    @Test
    void aChannelRejectionCarriesInteraktsErrorCode() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);
        sendBookingConfirmed(token, customerId);

        String failed = statusWebhook("message_api_failed",
                "\"channel_error_code\":\"1013\",\"channel_failure_reason\":\"Re-engagement message\",");
        webhook(failed).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

        WhatsAppMessage row = messages.findAll().get(0);
        assertThat(row.getStatus()).isEqualTo(WhatsAppMessage.Status.FAILED);
        assertThat(row.getChannelErrorCode()).isEqualTo("1013");
        assertThat(row.getLastError()).contains("Re-engagement");
    }

    @Test
    void anUnknownEventTypeIsAcknowledgedSoInteraktDoesNotRetryForever() throws Exception {
        webhook("{\"type\":\"message_api_something_new\",\"data\":{}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    void anUncorrelatedStatusEventIsAcknowledgedNotAnError() throws Exception {
        // E.g. a message sent outside this system, or one whose send response
        // was lost before we recorded the id.
        webhook(uncorrelatedStatusWebhook("message_api_delivered"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(0));
    }

    @Test
    void inboundReplyIsAttributedToTheConversationItAnswers() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);
        sendBookingConfirmed(token, customerId);

        String reply = """
                {"type":"message_received","data":{
                  "customer":{"id":"c1","phone_number":"%s","country_code":"91"},
                  "message":{"id":"inbound-1","chat_message_type":"TEXT",
                             "message":"Please share the payment link"}}}""".formatted(MOBILE);
        webhook(reply).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

        // A reply carries no lead/booking id, so it lands on the subject of our
        // most recent message to that number.
        TimelineEvent received = timeline.findAll().stream()
                .filter(e -> e.getKind() == TimelineEvent.Kind.REPLY_RECEIVED)
                .findFirst().orElseThrow();
        assertThat(received.getSubjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(received.getSubjectId().toString()).isEqualTo(customerId);
        assertThat(received.getDirection()).isEqualTo(TimelineEvent.Direction.INBOUND);
        assertThat(received.getSummary()).contains("payment link");
    }

    @Test
    void inboundMediaIsRecordedAsMediaNotAsText() throws Exception {
        String token = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer(MOBILE);
        sendBookingConfirmed(token, customerId);

        String media = """
                {"type":"message_received","data":{
                  "customer":{"id":"c1","phone_number":"%s","country_code":"91"},
                  "message":{"id":"inbound-2","chat_message_type":"IMAGE",
                             "message_content_type":"IMAGE",
                             "media_url":"https://cdn.interakt.ai/x.jpg"}}}""".formatted(MOBILE);
        webhook(media).andExpect(jsonPath("$.applied").value(1));

        assertThat(kindsFor(customerId)).contains(TimelineEvent.Kind.MEDIA_RECEIVED);
    }

    @Test
    void aReplyFromAnUnknownNumberIsStillCapturedAsANewLead() throws Exception {
        // Phase 5 Module 2 reverses the old anti-spam behaviour. This used to
        // assert the message was dropped ("random spam" from a number we have
        // never seen, and we had no way to answer it). Now an unmatched
        // inbound message is captured and a lead is created, because refusing
        // to record it is indistinguishable from never having received it --
        // the business cannot answer a question it cannot see.
        String reply = """
                {"type":"message_received","data":{
                  "customer":{"id":"c9","phone_number":"9000000000","country_code":"91"},
                  "message":{"id":"inbound-3","message":"random spam"}}}""";
        webhook(reply).andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));

        // 10 digits, no +91: PhoneUtils.normalize is the single definition of
        // a stored mobile, so a lead created from any channel lands on the same
        // digits and dedup keeps working.
        Lead captured = leadRepository.findFirstActiveDuplicate("9000000000").orElseThrow();
        assertThat(captured.getSource()).isEqualTo(Lead.Source.WHATSAPP);
        assertThat(captured.getStatus()).isEqualTo(Lead.Status.NEW);
        // Contact consent, never marketing consent: they messaged us, which
        // says nothing about whether they want to be marketed to.
        assertThat(captured.isConsentGiven()).isTrue();
        assertThat(captured.getConsentScope()).contains("NOT marketing");

        InboundMessage stored = inboundMessages
                .findByProviderAndProviderMessageId(InteraktWhatsAppGateway.PROVIDER, "inbound-3")
                .orElseThrow();
        assertThat(stored.getLeadId()).isEqualTo(captured.getId());
        assertThat(stored.getBody()).isEqualTo("random spam");
    }

    // ------------------------------------------------------------------ authorisation

    @Test
    void oneSalespersonCannotReadOrSendOnAnotherSalespersonsLead() throws Exception {
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String manager = managerToken();
        String priya = salesToken("priya", "priya@securetravels.in");
        String tripId = createTrip(manager);
        String leadId = createLead(ravi, tripId);

        // Priya cannot even list the messages, and cannot send to that lead either.
        mockMvc.perform(get("/api/whatsapp/messages")
                        .header("Authorization", authHeader(priya))
                        .param("subjectType", "LEAD")
                        .param("subjectId", leadId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(priya))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(SubjectType.LEAD, leadId, "PACKAGE_DETAILS",
                                List.of("Kashmir", "6 days", "25000", "2026-11-02"))))
                .andExpect(status().isForbidden());

        // The owner can.
        mockMvc.perform(get("/api/whatsapp/messages")
                        .header("Authorization", authHeader(ravi))
                        .param("subjectType", "LEAD")
                        .param("subjectId", leadId))
                .andExpect(status().isOk());
    }

    @Test
    void aManagerCanReadAnySubjectsCommunications() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        String manager = login("manager@securetravels.in", "manager123");
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager);
        String leadId = createLead(ravi, tripId);

        mockMvc.perform(get("/api/whatsapp/messages")
                        .header("Authorization", authHeader(manager))
                        .param("subjectType", "LEAD")
                        .param("subjectId", leadId))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ booking trigger

    @Test
    void confirmingABookingSendsTheCustomerTheirConfirmation() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager);
        String batchId = createBatch(manager, tripId, "2026-11-02", 10);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, MOBILE);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // The trigger is automatic: no operator action, no API call to /whatsapp.
        WhatsAppMessage sent = messages.findAll().stream()
                .filter(m -> m.getTemplateCode().equals("BOOKING_CONFIRMED"))
                .findFirst().orElseThrow();
        assertThat(sent.getSubjectType()).isEqualTo(SubjectType.BOOKING);
        assertThat(sent.getSubjectId().toString()).isEqualTo(bookingId);
        assertThat(sent.getRecipientMobile()).isEqualTo(MOBILE);
        assertThat(sent.getStatus()).isEqualTo(WhatsAppMessage.Status.SENT);
        assertThat(sent.bodyValues()).hasSize(4).doesNotContainNull();
    }

    @Test
    void anUndeliverableBookingConfirmationDoesNotUndoTheBooking() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager);
        String batchId = createBatch(manager, tripId, "2026-12-02", 10);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, MOBILE);

        // Take the template out of service, so the notifier cannot queue a
        // confirmation. (A failure *after* queueing is a different path, covered
        // by WhatsAppSenderTest.permanentFailureIsNotRetried; what matters here is
        // that no messaging outcome at all can reach back into the booking.)
        jdbcTemplate.update("UPDATE whatsapp_templates SET enabled = false WHERE code = 'BOOKING_CONFIRMED'");

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // The booking is confirmed and the seats are held, even though the
        // customer was never messaged. This is the whole reason the send is
        // AFTER_COMMIT: messaging is downstream of a fact, not part of it.
        assertThat(batchRepository.findById(UUID.fromString(batchId)).orElseThrow().getSeatsBooked()).isEqualTo(2);
        assertThat(messages.findAll().stream()
                .filter(m -> m.getTemplateCode().equals("BOOKING_CONFIRMED"))
                .toList()).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions sendAndRead(String token, String subjectId,
                                                                           List<String> values) throws Exception {
        return mockMvc.perform(post("/api/whatsapp/send")
                .header("Authorization", authHeader(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(sendBody(subjectId, "BOOKING_CONFIRMED", values)));
    }

    /** Send the standard BOOKING_CONFIRMED and leave exactly one row behind. */
    private void sendBookingConfirmed(String token, String subjectId) throws Exception {
        sendAndRead(token, subjectId, List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))
                .andExpect(status().isAccepted());
    }

    /**
     * A status webhook correlated by the {@code callback_data} we set at send
     * time, which is how a real Interakt delivery is matched: the provider id
     * only exists on the live gateway, and even there it is the fallback.
     */
    private String statusWebhook(String type, String extraMessageFields) {
        return """
                {"type":"%s","data":{"message":{"id":"sandbox-ignored",%s
                "message_status":"Delivered",
                "meta_data":{"source":"api",
                "source_data":{"callback_data":"%s"}}}}}"""
                .formatted(type, extraMessageFields, callbackData());
    }

    private String callbackData() {
        return messages.findAll().get(0).getCallbackData();
    }

    /**
     * No correlation token and an id we never issued. Acknowledged, because
     * returning an error here would make Interakt retry a message we have
     * simply never seen.
     */
    private String uncorrelatedStatusWebhook(String type) {
        return """
                {"type":"%s","data":{"message":{"id":"never-sent-%s",
                "message_status":"Delivered"}}}""".formatted(type, UUID.randomUUID());
    }

    private org.springframework.test.web.servlet.ResultActions webhook(String body) throws Exception {
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        return mockMvc.perform(post("/api/webhook/interakt")
                .contentType(MediaType.APPLICATION_JSON)
                .header(InteraktWebhookController.SIGNATURE_HEADER, HmacSigner.sign(WEBHOOK_SECRET, raw))
                .content(raw));
    }

    private List<TimelineEvent.Kind> kindsFor(String subjectId) {
        return timeline.findAll().stream()
                .filter(e -> e.getSubjectId().toString().equals(subjectId))
                .map(TimelineEvent::getKind)
                .toList();
    }

    private String sendBody(String subjectId, String templateCode, List<String> values) throws Exception {
        return sendBody(SubjectType.CUSTOMER, subjectId, templateCode, values);
    }

    private String sendBody(SubjectType subjectType, String subjectId,
                            String templateCode, List<String> values) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectType", subjectType.name());
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
        return customerRepository.save(Customer360.fromLead("Asha Rao", phone, phone, phone,
                "asha" + phone + "@example.com", true, "WhatsApp module 4 IT")).getId().toString();
    }

    private String createTrip(String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Kashmir Splendor");
        body.put("category", "PILGRIMAGE");
        body.put("bookingType", "FIXED_BATCH");
        body.put("baseCost", 25000);
        body.put("durationDays", 6);
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBatch(String token, String tripId, String departureDate, int capacity) throws Exception {
        String created = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureDate", departureDate, "maxCapacity", capacity))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createLead(String token, String tripId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Amit Verma");
        body.put("mobileNumber", MOBILE);
        body.put("source", "WEBSITE");
        body.put("destination", "Kashmir");
        body.put("tripId", tripId);
        body.put("consentGiven", true);
        String created = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBookingFromCustomer(String token, String tripId, String batchId,
                                             int travellers, String phone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", newCustomer(phone));
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", travellers);
        body.put("travellers", java.util.stream.IntStream.range(0, travellers)
                .mapToObj(i -> Map.<String, Object>of("fullName", "Traveller " + (i + 1),
                        "age", 30 + i, "gender", i % 2 == 0 ? "M" : "F", "medicalCertRequired", false))
                .toList());
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String bookingId = objectMapper.readTree(created).get("id").asText();
        assertThat(leadRepository.findAll()).isNotNull();
        return bookingId;
    }
}
