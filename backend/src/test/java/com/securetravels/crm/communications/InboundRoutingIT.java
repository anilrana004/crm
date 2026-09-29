package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.communications.consent.ConsentService;
import com.securetravels.crm.communications.consent.ConsentStatus;
import com.securetravels.crm.communications.email.SesEmailGateway;
import com.securetravels.crm.communications.inbound.InboundMessage;
import com.securetravels.crm.communications.inbound.InboundMessageRepository;
import com.securetravels.crm.communications.sms.Msg91SmsGateway;
import com.securetravels.crm.communications.thread.CommunicationChannel;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 2 inbound: SMS replies and SES email forwards are routed through the
 * same inbound entry point as WhatsApp (Phase 5 Module 2).
 *
 * <p>What is asserted here that {@code WhatsAppCommunicationIT} does not is the
 * guarantee of centralisation itself: a reply on SMS or email is deduplicated on
 * the gateway's provider id, resolves to a known customer or creates a lead for
 * a stranger, and — for a STOP keyword — revokes consent exactly once no matter
 * how many times the gateway delivers the same message.
 */
@DisplayName("Module 2: centralized inbound routing")
class InboundRoutingIT extends BaseIT {

    private static final String MOBILE = "9876500000";
    private static final String STRANGER = "9001111111";
    private static final String ADDRESS = "asha.rao@example.com";

    @Autowired private Customer360Repository customerRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private InboundMessageRepository inboundMessages;
    @Autowired private TimelineEventRepository timeline;
    @Autowired private ConsentService consent;

    // ------------------------------------------------------------------ SMS inbound

    @Test
    @DisplayName("an SMS reply from an unknown number creates one lead, and a retry adds nothing")
    void smsReplyFromAnUnknownNumberIsCapturedOnce() throws Exception {
        String body = smsReply(STRANGER, "sms-unknown-1", "Please share the tour details");

        webhookSms(body).andExpect(status().isOk()).andExpect(content().string("1"));

        InboundMessage stored = inboundMessages
                .findByProviderAndProviderMessageId(Msg91SmsGateway.PROVIDER, "sms-unknown-1")
                .orElseThrow();
        assertThat(stored.getChannel()).isEqualTo(CommunicationChannel.SMS);
        assertThat(stored.getFromMobile()).isEqualTo("9001111111");
        assertThat(stored.getBody()).contains("tour details");

        Lead captured = leadRepository.findFirstActiveDuplicate(STRANGER).orElseThrow();
        assertThat(captured.getSource()).isEqualTo(Lead.Source.OTHER);
        assertThat(captured.getStatus()).isEqualTo(Lead.Status.NEW);
        // They messaged us: contact consent granted, never marketing consent.
        assertThat(captured.isConsentGiven()).isTrue();
        assertThat(captured.getConsentScope()).contains("NOT marketing");

        // The gateway's retry of the same provider id must add no second row.
        webhookSms(body).andExpect(status().isOk()).andExpect(content().string("0"));
        assertThat(inboundMessages.findAll()).hasSize(1);
        assertThat(leadRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("an SMS reply from a known customer is attributed to that customer")
    void smsReplyFromAKnownCustomerIsAttributed() throws Exception {
        String customerId = newCustomer();

        webhookSms(smsReply(MOBILE, "sms-cust-1", "Is the itinerary final?"))
                .andExpect(status().isOk()).andExpect(content().string("1"));

        InboundMessage stored = inboundMessages
                .findByProviderAndProviderMessageId(Msg91SmsGateway.PROVIDER, "sms-cust-1")
                .orElseThrow();
        assertThat(stored.getSubjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(stored.getSubjectId().toString()).isEqualTo(customerId);

        assertThat(events(SubjectType.CUSTOMER, customerId))
                .anyMatch(e -> e.getChannel() == TimelineEvent.Channel.SMS
                        && e.getKind() == TimelineEvent.Kind.REPLY_RECEIVED);
    }

    @Test
    @DisplayName("an SMS STOP revokes consent once, even when the gateway retries")
    void smsStopRevokesConsentOnceAcrossRetries() throws Exception {
        String customerId = newCustomer();
        String body = smsReply(MOBILE, "sms-stop-1", "STOP");

        webhookSms(body).andExpect(status().isOk()).andExpect(content().string("1"));
        assertThat(consent.effectiveForMobile(MOBILE, TimelineEvent.Channel.SMS))
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(countRevoked(customerId, "SMS")).isEqualTo(1);

        // A retried delivery of the same STOP must not revoke a second time.
        webhookSms(body).andExpect(status().isOk()).andExpect(content().string("0"));
        assertThat(countRevoked(customerId, "SMS")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ email inbound

    @Test
    @DisplayName("an SES email forward is attributed to the customer and deduplicated")
    void emailReplyFromAKnownCustomerIsAttributed() throws Exception {
        String customerId = newCustomer();

        webhookEmail(receivedMail("ses-mail-1", "Re: your trip",
                "From: Asha Rao <" + ADDRESS + ">\nTo: bookings@securetravels.example\n"
                        + "Subject: Re: your trip\n\nPlease send the itinerary."))
                .andExpect(status().isOk()).andExpect(content().string("1"));

        InboundMessage stored = inboundMessages
                .findByProviderAndProviderMessageId(SesEmailGateway.PROVIDER, "ses-mail-1")
                .orElseThrow();
        assertThat(stored.getChannel()).isEqualTo(CommunicationChannel.EMAIL);
        assertThat(stored.getFromEmail()).isEqualTo(ADDRESS);
        assertThat(stored.getBody()).contains("Please send the itinerary");
        assertThat(stored.getSubjectType()).isEqualTo(SubjectType.CUSTOMER);
        assertThat(stored.getSubjectId().toString()).isEqualTo(customerId);

        assertThat(events(SubjectType.CUSTOMER, customerId))
                .anyMatch(e -> e.getChannel() == TimelineEvent.Channel.EMAIL
                        && e.getKind() == TimelineEvent.Kind.REPLY_RECEIVED);

        // SES retries at least once; the duplicate must not be stored twice.
        webhookEmail(receivedMail("ses-mail-1", "Re: your trip",
                "From: Asha Rao <" + ADDRESS + ">\nTo: bookings@securetravels.example\n"
                        + "Subject: Re: your trip\n\nPlease send the itinerary."))
                .andExpect(status().isOk()).andExpect(content().string("0"));
        assertThat(inboundMessages.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("an email reply saying stop suppresses the address once across retries")
    void emailOptOutKeywordSuppressesOnce() throws Exception {
        String customerId = newCustomer();
        String body = receivedMail("ses-stop-1", "Re: trip",
                "From: Asha Rao <" + ADDRESS + ">\nTo: bookings@securetravels.example\n"
                        + "Subject: Re: trip\n\nUNSUBSCRIBE");

        webhookEmail(body).andExpect(status().isOk()).andExpect(content().string("1"));
        assertThat(consent.effectiveForEmail(ADDRESS, TimelineEvent.Channel.EMAIL))
                .isEqualTo(ConsentStatus.REVOKED);
        assertThat(countSuppressed(ADDRESS)).isEqualTo(1);

        webhookEmail(body).andExpect(status().isOk()).andExpect(content().string("0"));
        assertThat(countSuppressed(ADDRESS)).isEqualTo(1);
        assertThat(countRevoked(customerId, "EMAIL")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions webhookSms(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.getBytes(StandardCharsets.UTF_8)));
    }

    private org.springframework.test.web.servlet.ResultActions webhookEmail(String raw) throws Exception {
        return mockMvc.perform(post("/api/v1/webhooks/email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(raw.getBytes(StandardCharsets.UTF_8)));
    }

    private String smsReply(String mobile, String messageId, String text) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "type", "INBOUND", "messageId", messageId, "mobile", mobile, "text", text));
    }

    private String receivedMail(String messageId, String subject, String rawMessage) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "notificationType", "Received",
                "content", Base64.getEncoder().encodeToString(rawMessage.getBytes(StandardCharsets.UTF_8)),
                "mail", Map.of(
                        "messageId", messageId,
                        "source", ADDRESS,
                        "timestamp", "2026-09-29T10:00:00Z",
                        "commonHeaders", Map.of(
                                "from", List.of("Asha Rao <" + ADDRESS + ">"),
                                "to", List.of("bookings@securetravels.example"),
                                "subject", subject))));
    }

    private List<TimelineEvent> events(SubjectType type, String subjectId) {
        return timeline.findAll().stream()
                .filter(e -> e.getSubjectType() == type && e.getSubjectId().toString().equals(subjectId))
                .toList();
    }

    private int countRevoked(String customerId, String channel) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consent_records"
                        + " WHERE customer_id = ?::uuid AND channel = ? AND status = 'REVOKED'",
                Integer.class, customerId, channel);
        return n == null ? 0 : n;
    }

    private int countSuppressed(String address) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM consent_suppressions WHERE email_address = ?",
                Integer.class, address);
        return n == null ? 0 : n;
    }

    private String newCustomer() {
        return customerRepository.save(Customer360.fromLead("Asha Rao", MOBILE, MOBILE, MOBILE,
                ADDRESS, true, "Module 2 inbound IT")).getId().toString();
    }
}