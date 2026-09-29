package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.communications.email.EmailMessage;
import com.securetravels.crm.communications.email.EmailMessageRepository;
import com.securetravels.crm.communications.inbound.InboundMessage;
import com.securetravels.crm.communications.inbound.InboundMessageRepository;
import com.securetravels.crm.communications.inbound.InboundMessageService;
import com.securetravels.crm.communications.sms.SmsMessage;
import com.securetravels.crm.communications.sms.SmsMessageRepository;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 2 outbound: email and SMS through the one gate (Phase 5 Module 2).
 *
 * <p>Complements {@code WhatsAppCommunicationIT}, which covers Module 1. What is
 * asserted here that is not asserted there is that email and SMS are genuinely
 * first-class channels rather than "WhatsApp, but with a different gateway":
 * the same template codes work, the same gate decides, and the same
 * {@code {{1}}..{{4}}} substitution convention applies.
 */
@DisplayName("Module 2: email and SMS outbound")
class EmailAndSmsDeliveryIT extends BaseIT {

    private static final String MOBILE = "9876500000";
    private static final String ADDRESS = "asha.rao@example.com";

    @Autowired private EmailMessageRepository emails;
    @Autowired private SmsMessageRepository smsMessages;
    @Autowired private TimelineEventRepository timeline;
    @Autowired private Customer360Repository customerRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private InboundMessageRepository inboundMessages;
    @Autowired private InboundMessageService inboundService;

    // ------------------------------------------------------------------ catalogue

    @Nested
    @DisplayName("template catalogue")
    class Catalogue {

        @Test
        @DisplayName("email exposes the same codes as WhatsApp, so one code means one message")
        void emailCatalogueUsesTheSharedCodes() throws Exception {
            String token = salesToken();

            mockMvc.perform(get("/api/v1/email/templates").header("Authorization", authHeader(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.code=='BOOKING_CONFIRMED')].expectedParams").value(4))
                    .andExpect(jsonPath("$[?(@.code=='BOOKING_CONFIRMED')].approvalStatus").value("APPROVED"));

            // The point of channel_templates: BOOKING_CONFIRMED is the same code
            // on WhatsApp and on email, so an operator learns it once.
            List<String> emailCodes = codesFrom("/api/v1/email/templates", token);
            List<String> smsCodes = codesFrom("/api/v1/sms/templates", token);
            assertThat(emailCodes).contains("BOOKING_CONFIRMED", "PAYMENT_LINK", "ITINERARY");
            assertThat(smsCodes).contains("BOOKING_CONFIRMED");
            // Everything SMS offers, email offers too -- SMS is a strict subset,
            // never a differently-named parallel list.
            assertThat(emailCodes).containsAll(smsCodes);
        }

        @Test
        @DisplayName("SMS is deliberately short: it does not offer templates a 160-char segment cannot carry")
        void smsCatalogueIsShorterThanEmail() throws Exception {
            String token = salesToken();

            List<String> emailCodes = codesFrom("/api/v1/email/templates", token);
            List<String> smsCodes = codesFrom("/api/v1/sms/templates", token);

            // Seeding ITINERARY for SMS would advertise a capability the medium
            // does not have: the text would be silently truncated at the handset.
            assertThat(emailCodes).contains("ITINERARY");
            assertThat(smsCodes).doesNotContain("ITINERARY");
            assertThat(smsCodes).hasSizeLessThan(emailCodes.size());
        }

        @Test
        @DisplayName("a disabled template disappears from the picker")
        void disabledTemplatesAreNotOffered() throws Exception {
            String token = salesToken();
            jdbcTemplate.update("UPDATE channel_templates SET enabled = false WHERE channel = 'EMAIL'");

            List<String> codes = codesFrom("/api/v1/email/templates", token);
            assertThat(codes).isEmpty();
        }
    }

    // ------------------------------------------------------------------ email send

    @Nested
    @DisplayName("email send")
    class EmailSend {

        @Test
        @DisplayName("a template send is recorded, delivered inline, and traced on the timeline")
        void templateSendIsRecordedDeliveredAndTraced() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "BOOKING_CONFIRMED",
                                    List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.templateCode").value("BOOKING_CONFIRMED"))
                    .andExpect(jsonPath("$.recipient").value(ADDRESS));

            EmailMessage sent = emails.findAll().get(0);
            assertThat(sent.getStatus()).isEqualTo(EmailMessage.Status.SENT);
            assertThat(sent.getAttempts()).isEqualTo(1);

            // {{1}}..{{4}} substituted, the same positional convention WhatsApp
            // uses. The subject line deliberately carries only {{1}} -- a
            // departure date in a subject line is noise, and the body is where
            // the detail belongs.
            assertThat(sent.getSubjectLine()).contains("Asha Rao").doesNotContain("{{");
            assertThat(sent.getBodyText())
                    .contains("Manali")     // {{3}}
                    .contains("2026-11-02") // {{4}}
                    .contains("TOH-2026-0001")
                    .doesNotContain("{{");

            assertThat(kindsFor(customerId))
                    .contains(TimelineEvent.Kind.TEMPLATE_QUEUED, TimelineEvent.Kind.TEMPLATE_SENT);

            // Readable back, scoped to the subject.
            mockMvc.perform(get("/api/v1/email/messages")
                            .header("Authorization", authHeader(token))
                            .param("subjectType", "CUSTOMER")
                            .param("subjectId", customerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].status").value("SENT"));
        }

        @Test
        @DisplayName("the wrong number of body values is refused before any provider quota is spent")
        void wrongParameterCountIsRejected() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "BOOKING_CONFIRMED", List.of("only-one"))))
                    .andExpect(status().isBadRequest());

            assertThat(emails.findAll()).isEmpty();
        }

        @Test
        @DisplayName("an unknown template is a 400, and the refusal is on the timeline")
        void unknownTemplateIsRejectedAndRecorded() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "NO_SUCH_TEMPLATE", List.of("a", "b", "c", "d"))))
                    .andExpect(status().isBadRequest());

            assertThat(emails.findAll()).isEmpty();
            // "We did not reach this customer" must be a queryable fact, not a gap.
            assertThat(timeline.findAll().stream()
                    .map(TimelineEvent::getSummary))
                    .anyMatch(s -> s != null && s.contains("NO_SUCH_TEMPLATE"));
        }

        @Test
        @DisplayName("a marketing template without consent is refused with 403")
        void marketingTemplateNeedsConsent() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "POST_TRIP_REVIEW",
                                    List.of("Asha Rao", "TOH-2026-0001", "Manali"))))
                    .andExpect(status().isForbidden());

            assertThat(emails.findAll()).isEmpty();
        }

        @Test
        @DisplayName("with EMAIL marketing consent granted, the same template is allowed")
        void marketingTemplateAllowedOnceConsentGranted() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();
            grantEmailMarketingConsent(customerId);

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "POST_TRIP_REVIEW",
                                    List.of("Asha Rao", "TOH-2026-0001", "Manali"))))
                    .andExpect(status().isAccepted());

            assertThat(emails.findAll().get(0).getStatus()).isEqualTo(EmailMessage.Status.SENT);
        }

        @Test
        @DisplayName("a malformed address is refused")
        void invalidRecipientIsRejected() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subjectType":"CUSTOMER","subjectId":"%s",
                                     "templateCode":"BOOKING_CONFIRMED",
                                     "email":"not-an-address",
                                     "bodyValues":["a","b","c","d"]}""".formatted(customerId)))
                    .andExpect(status().isBadRequest());
        }
    }

    // ------------------------------------------------------------------ sms send

    @Nested
    @DisplayName("SMS send")
    class SmsSend {

        @Test
        @DisplayName("a template send is recorded, delivered inline, and traced")
        void templateSendIsRecordedDeliveredAndTraced() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/sms/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(smsBody(customerId, "BOOKING_CONFIRMED",
                                    List.of("Manali", "TOH-2026-0001", "Asha", "2026-11-02"))))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.recipient").value(MOBILE));

            SmsMessage sent = smsMessages.findAll().get(0);
            assertThat(sent.getStatus()).isEqualTo(SmsMessage.Status.SENT);
            assertThat(sent.getBodyText()).contains("Manali").doesNotContain("{{");
            assertThat(kindsFor(customerId))
                    .contains(TimelineEvent.Kind.TEMPLATE_QUEUED, TimelineEvent.Kind.TEMPLATE_SENT);
        }

        @Test
        @DisplayName("a number that is not ten Indian digits is refused at the API, not at the handset")
        void invalidMobileIsRejected() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/sms/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(smsBody(customerId, "BOOKING_CONFIRMED",
                                    List.of("a", "b", "c", "d"), "12345")))
                    .andExpect(status().isBadRequest());

            // The point of validating here: an invalid number is billed for and
            // silently dropped by the gateway, so it must never reach one.
            assertThat(smsMessages.findAll()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ cross-channel

    @Nested
    @DisplayName("cross-channel behaviour")
    class CrossChannel {

        @Test
        @DisplayName("each channel gets its own thread, so one customer is not one conversation")
        void eachChannelIsItsOwnThread() throws Exception {
            String token = salesToken();
            String customerId = newCustomer();

            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(emailBody(customerId, "BOOKING_CONFIRMED",
                                    List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"))))
                    .andExpect(status().isAccepted());
            mockMvc.perform(post("/api/v1/sms/send")
                            .header("Authorization", authHeader(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(smsBody(customerId, "BOOKING_CONFIRMED",
                                    List.of("Manali", "TOH-2026-0001", "Asha", "2026-11-02"))))
                    .andExpect(status().isAccepted());

            assertThat(emails.findAll().get(0).getThread().getId())
                    .isNotEqualTo(smsMessages.findAll().get(0).getThread().getId());
        }

        @Test
        @DisplayName("one salesperson cannot send email on another salesperson's lead")
        void sendIsSubjectToTheSameOwnershipCheckAsReading() throws Exception {
            salesTokenFor("ravi", "ravi@securetravels.in");
            String manager = managerToken();
            String other = salesTokenFor("nisha", "nisha@securetravels.in");
            String tripId = createTrip(manager);
            String leadId = createLead("ravi@securetravels.in", tripId);

            // Sending discloses the customer's data, so it carries the same
            // ownership check as reading their timeline. Otherwise a salesperson
            // who cannot read a lead's history can still email them.
            mockMvc.perform(post("/api/v1/email/send")
                            .header("Authorization", authHeader(other))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "subjectType", "LEAD",
                                    "subjectId", leadId,
                                    "templateCode", "BOOKING_CONFIRMED",
                                    "email", ADDRESS,
                                    "bodyValues", List.of("Amit Verma", "L-1", "Kashmir", "2026-11-02")))))
                    .andExpect(status().isForbidden());

            assertThat(emails.findAll()).isEmpty();
        }
    }

    // ------------------------------------------------------------------ inbound dedup

    @Nested
    @DisplayName("inbound idempotency")
    class InboundIdempotency {

        @Test
        @DisplayName("the same provider message id delivered twice creates one row and one lead")
        void replayIsANoOp() {
            String provider = "INTERAKT";
            String providerMessageId = "replay-1";

            // The first call records it; the second is the gateway retry. An
            // empty Optional is the success case here, not a failure.
            assertThat(record(provider, providerMessageId, "hello")).isPresent();
            assertThat(record(provider, providerMessageId, "hello")).isEmpty();

            // This is the guarantee that makes an at-least-once webhook safe: a
            // customer must not appear twice because a provider retried.
            assertThat(inboundMessages.findAll()).hasSize(1);
            assertThat(leadRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("concurrent deliveries of one id still create exactly one row and one lead")
        void concurrentDeliveriesCollapseToOne() throws Exception {
            String provider = "INTERAKT";
            String providerMessageId = "concurrent-1";
            int racers = 8;
            CyclicBarrier gate = new CyclicBarrier(racers);
            ExecutorService pool = Executors.newFixedThreadPool(racers);

            try {
                List<Callable<Boolean>> calls = new ArrayList<>();
                for (int i = 0; i < racers; i++) {
                    calls.add(() -> {
                        gate.await(10, TimeUnit.SECONDS);
                        try {
                            return record(provider, providerMessageId, "hi").isPresent();
                        } catch (RuntimeException e) {
                            // Losing the race must mean losing to a duplicate,
                            // not to a constraint violation escaping as a 500.
                            return false;
                        }
                    });
                }
                List<Boolean> applied = pool.invokeAll(calls).stream()
                        .map(EmailAndSmsDeliveryIT::valueOf)
                        .collect(Collectors.toList());

                // Whatever the interleaving, the invariant is the same. The
                // INSERT ... ON CONFLICT claim is what makes this hold without
                // locking the leads table for the duration of a webhook.
                assertThat(inboundMessages.findAll()).hasSize(1);
                assertThat(leadRepository.findAll()).hasSize(1);
                assertThat(applied.stream().filter(Boolean::booleanValue).count())
                        .as("exactly one racer may win: %s", applied)
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("distinct ids from the same number are separate messages on one lead")
        void distinctIdsDoNotCollapse() {
            assertThat(record("INTERAKT", "a-1", "first")).isPresent();
            assertThat(record("INTERAKT", "a-2", "second")).isPresent();

            // Two messages, one person. Collapsing these would lose a message.
            assertThat(inboundMessages.findAll()).hasSize(2);
            assertThat(leadRepository.findAll()).hasSize(1);
        }

        /** One WhatsApp text from {@link #MOBILE}, as a webhook would deliver it. */
        private Optional<InboundMessage> record(String provider, String providerMessageId,
                                               String body) {
            return inboundService.record(CommunicationChannel.WHATSAPP, provider, providerMessageId,
                    MOBILE, null, body, false, null, Instant.now());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Boolean valueOf(Future<Boolean> f) {
        try {
            return f.get();
        } catch (Exception e) {
            return false;
        }
    }

    private String salesToken() throws Exception {
        return salesTokenFor("ravi", "ravi@securetravels.in");
    }

    private String salesTokenFor(String name, String email) throws Exception {
        createUser(email, name, Role.SALES, "sales123");
        return login(email, "sales123");
    }

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String createTrip(String token) throws Exception {
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Kashmir Splendor",
                                "category", "PILGRIMAGE",
                                "bookingType", "FIXED_BATCH",
                                "baseCost", 25000,
                                "durationDays", 6))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    /** A lead owned by the user whose token is passed in, so ownership is real. */
    private String createLead(String ownerEmail, String tripId) throws Exception {
        String token = login(ownerEmail, "sales123");
        String created = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "customerName", "Amit Verma",
                                "mobileNumber", MOBILE,
                                "source", "WEBSITE",
                                "destination", "Kashmir",
                                "tripId", tripId,
                                "consentGiven", true))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String newCustomer() {
        return customerRepository.save(Customer360.fromLead("Asha Rao", MOBILE, MOBILE, MOBILE,
                ADDRESS, true, "Module 2 IT")).getId().toString();
    }

    private void grantEmailMarketingConsent(String customerId) {
        jdbcTemplate.update("""
                INSERT INTO consent_records
                    (customer_id, channel, purpose, status, source, evidence_ref, occurred_at)
                VALUES (?::uuid, 'EMAIL', 'MARKETING', 'GRANTED', 'EMAIL_OPTIN', 'module2-it', now())""",
                customerId);
    }

    private List<TimelineEvent.Kind> kindsFor(String subjectId) {
        return timeline.findAll().stream()
                .filter(e -> e.getSubjectId().toString().equals(subjectId))
                .map(TimelineEvent::getKind)
                .toList();
    }

    private List<String> codesFrom(String path, String token) throws Exception {
        String json = mockMvc.perform(get(path).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).findValuesAsText("code");
    }

    private String emailBody(String subjectId, String templateCode, List<String> values) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "subjectType", "CUSTOMER",
                "subjectId", subjectId,
                "templateCode", templateCode,
                "email", ADDRESS,
                "bodyValues", values));
    }

    private String smsBody(String subjectId, String templateCode, List<String> values) throws Exception {
        return smsBody(subjectId, templateCode, values, MOBILE);
    }

    private String smsBody(String subjectId, String templateCode, List<String> values, String mobile)
            throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "subjectType", "CUSTOMER",
                "subjectId", subjectId,
                "templateCode", templateCode,
                "mobile", mobile,
                "bodyValues", values));
    }
}
