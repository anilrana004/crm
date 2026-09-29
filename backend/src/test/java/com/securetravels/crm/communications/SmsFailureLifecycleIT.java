package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.communications.email.EmailMessage;
import com.securetravels.crm.communications.email.EmailMessageRepository;
import com.securetravels.crm.communications.sms.SmsGateway;
import com.securetravels.crm.communications.sms.SmsMessage;
import com.securetravels.crm.communications.sms.SmsMessageRepository;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What happens to a send the provider refuses (Phase 5 Module 2).
 *
 * <p>Uses a stubbed gateway rather than the sandbox one, because the sandbox
 * always succeeds and the interesting behaviour is entirely in the failure
 * branches. The three outcomes must stay distinguishable:
 *
 * <ul>
 *   <li><strong>retryable</strong> — back to {@code QUEUED}, retried, and
 *       dead-lettered only once the budget is spent;</li>
 *   <li><strong>permanent</strong> — {@code FAILED} on the first try, so a
 *       rejected DLT template is not paid for three times over;</li>
 *   <li><strong>thrown</strong> — treated as retryable, because a network
 *       glitch should not permanently kill a message to a paying customer.</li>
 * </ul>
 */
@DisplayName("Module 2: provider failures")
class SmsFailureLifecycleIT extends BaseIT {

    private static final String MOBILE = "9876500000";
    private static final String ADDRESS = "asha.rao@example.com";

    /** Replaces the sandbox gateway for this class only. */
    @MockBean private SmsGateway gateway;

    @Autowired private SmsMessageRepository smsMessages;
    @Autowired private Customer360Repository customerRepository;

    @Test
    @DisplayName("a retryable failure is retried and then dead-lettered once the budget is spent")
    void retryableFailureEventuallyDeadLetters() throws Exception {
        Mockito.when(gateway.provider()).thenReturn("TEST");
        Mockito.when(gateway.send(Mockito.any()))
                .thenReturn(SmsGateway.SendResult.failure("HTTP_503", "upstream busy", true));

        send();

        SmsMessage row = only();
        // Dead-lettered, not FAILED. The two mean different things: FAILED is
        // still owed an attempt, DEAD_LETTERED is not. Conflating them leaves a
        // row that a recovery sweep will pick up forever.
        assertThat(row.getStatus()).isEqualTo(SmsMessage.Status.DEAD_LETTERED);
        assertThat(row.getAttempts())
                .as("every attempt in the budget is spent, then it gives up")
                .isEqualTo(3);
        assertThat(row.getLastError()).isNotBlank();
    }

    @Test
    @DisplayName("a permanent rejection is terminal on the first try")
    void permanentFailureIsNotRetried() throws Exception {
        Mockito.when(gateway.provider()).thenReturn("TEST");
        Mockito.when(gateway.send(Mockito.any()))
                .thenReturn(SmsGateway.SendResult.failure("DLT_400", "template not registered", false));

        send();

        SmsMessage row = only();
        // One attempt, not three: the same DLT rejection will be returned every
        // time, and each retry is billed to the customer's phone.
        assertThat(row.getStatus()).isEqualTo(SmsMessage.Status.FAILED);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getFailureReason()).contains("template not registered");
    }

    @Test
    @DisplayName("a thrown gateway error is treated as retryable, not as a final failure")
    void thrownErrorIsRetryable() throws Exception {
        Mockito.when(gateway.provider()).thenReturn("TEST");
        // Unchecked, because that is all a gateway can throw: the interface
        // declares no checked exceptions, so an HTTP client failure reaches us
        // wrapped. What matters is that an unclassified throw is not mistaken
        // for a permanent refusal.
        Mockito.when(gateway.send(Mockito.any()))
                .thenThrow(new IllegalStateException("connection reset",
                        new java.net.SocketTimeoutException("read timed out")));

        send();

        // Unclassified, so the budget decides. Dying on a socket timeout would
        // mean losing the message over a blip the next attempt would survive.
        assertThat(only().getStatus()).isEqualTo(SmsMessage.Status.DEAD_LETTERED);
        assertThat(only().getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("a transient failure that clears is delivered, and the row says so")
    void retryRecoversWhenTheProviderRecovers() throws Exception {
        Mockito.when(gateway.provider()).thenReturn("TEST");
        Mockito.when(gateway.send(Mockito.any()))
                .thenReturn(SmsGateway.SendResult.failure("HTTP_503", "upstream busy", true))
                .thenReturn(SmsGateway.SendResult.ok("provider-abc"));

        send();

        SmsMessage row = only();
        // The whole reason for returning to QUEUED instead of FAILED: the second
        // attempt succeeds and the customer still gets the message.
        assertThat(row.getStatus()).isEqualTo(SmsMessage.Status.SENT);
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(row.getProviderMessageId()).isEqualTo("provider-abc");
    }

    // ------------------------------------------------------------------ helpers

    private void send() throws Exception {
        mockMvc.perform(post("/api/v1/sms/send")
                        .header("Authorization", authHeader(token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "subjectType", "CUSTOMER",
                                "subjectId", newCustomer(),
                                "templateCode", "BOOKING_CONFIRMED",
                                "mobile", MOBILE,
                                "bodyValues", List.of("Manali", "T-1", "Asha", "2026-11-02")))))
                .andExpect(status().isAccepted());
    }

    private SmsMessage only() {
        assertThat(smsMessages.findAll()).hasSize(1);
        return smsMessages.findAll().get(0);
    }

    private String token() throws Exception {
        createUser("ravi@securetravels.in", "ravi", Role.SALES, "sales123");
        return login("ravi@securetravels.in", "sales123");
    }

    private String newCustomer() {
        return customerRepository.save(Customer360.fromLead("Asha Rao", MOBILE, MOBILE, MOBILE,
                ADDRESS, true, "SMS failure IT")).getId().toString();
    }
}
