package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.communications.email.EmailMessage;
import com.securetravels.crm.communications.email.EmailMessageRepository;
import com.securetravels.crm.communications.sms.SmsMessage;
import com.securetravels.crm.communications.sms.SmsMessageRepository;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery outcome must actually be committed.
 *
 * <p>Regression cover for a failure that no unit test could have caught:
 * {@code attempt()} is reached through an {@code AFTER_COMMIT} listener, where
 * Spring still reports the just-finished transaction as active. A
 * {@code TransactionTemplate} left at the default {@code REQUIRED} propagation
 * joins that phantom transaction, so the provider is called, the send genuinely
 * succeeds, and the write recording it is discarded — leaving the row at
 * {@code QUEUED} forever. The customer's mail arrives and the system maintains
 * it never did.
 *
 * <p>Worth pinning explicitly because the symptom is invisible in the logs: the
 * gateway reports success, the HTTP call returns 202, and only the database
 * disagrees.
 */
@DisplayName("Delivery outcomes are committed, not silently dropped")
class DeliveryOutcomeCommitIT extends BaseIT {

    private static final String MOBILE = "9876500000";
    private static final String ADDRESS = "asha.rao@example.com";

    @Autowired private EmailMessageRepository emails;
    @Autowired private SmsMessageRepository smsMessages;
    @Autowired private Customer360Repository customerRepository;

    @Test
    @DisplayName("an email the provider accepted is persisted as SENT, not left QUEUED")
    void emailOutcomeIsPersisted() throws Exception {
        String token = token();
        String customerId = newCustomer();

        mockMvc.perform(post("/api/v1/email/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectType", "CUSTOMER",
                                "subjectId", customerId,
                                "templateCode", "BOOKING_CONFIRMED",
                                "email", ADDRESS,
                                "bodyValues", List.of("Asha Rao", "T-1", "Manali", "2026-11-02")))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"));

        // Re-read through the repository rather than trusting the response body.
        // The response is built from the in-memory entity the phantom-transaction
        // write touched, so it can say SENT even when nothing reached the
        // database -- which is exactly what went wrong here.
        EmailMessage row = emails.findAll().get(0);
        assertThat(row.getStatus())
                .as("a delivered email must be recorded as sent")
                .isEqualTo(EmailMessage.Status.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an SMS the provider accepted is persisted as SENT, not left QUEUED")
    void smsOutcomeIsPersisted() throws Exception {
        String token = token();
        String customerId = newCustomer();

        mockMvc.perform(post("/api/v1/sms/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectType", "CUSTOMER",
                                "subjectId", customerId,
                                "templateCode", "BOOKING_CONFIRMED",
                                "mobile", MOBILE,
                                "bodyValues", List.of("Manali", "T-1", "Asha", "2026-11-02")))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("SENT"));

        SmsMessage row = smsMessages.findAll().get(0);
        assertThat(row.getStatus())
                .as("a delivered SMS must be recorded as sent")
                .isEqualTo(SmsMessage.Status.SENT);
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("an inline retry loop stops on a terminal outcome instead of burning the budget")
    void terminalOutcomeStopsTheLoop() throws Exception {
        // The mirror image of the bug above: a loop that keeps calling attempt()
        // on a row that will never be sent again is not free. The sandbox gateway
        // succeeds, so the loop must exit on its first pass.
        String token = token();
        String customerId = newCustomer();
        mockMvc.perform(post("/api/v1/email/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectType", "CUSTOMER",
                                "subjectId", customerId,
                                "templateCode", "BOOKING_CONFIRMED",
                                "email", ADDRESS,
                                "bodyValues", List.of("Asha Rao", "T-1", "Manali", "2026-11-02")))))
                .andExpect(status().isAccepted());

        // attempts == 1 is the observable proof: three would mean the loop did
        // not notice the row had left QUEUED.
        assertThat(emails.findAll().get(0).getAttempts()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String token() throws Exception {
        createUser("ravi@securetravels.in", "ravi", Role.SALES, "sales123");
        return login("ravi@securetravels.in", "sales123");
    }

    private String newCustomer() {
        return customerRepository.save(Customer360.fromLead("Asha Rao", MOBILE, MOBILE, MOBILE,
                ADDRESS, true, "Delivery outcome IT")).getId().toString();
    }
}
