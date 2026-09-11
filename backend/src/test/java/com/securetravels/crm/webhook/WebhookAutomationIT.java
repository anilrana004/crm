package com.securetravels.crm.webhook;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditLogRepository;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 9 — end-to-end automation chain via the public website webhook:
 * valid HMAC → lead → duplicate check → round-robin owner → INITIAL_CALL
 * task + in-app notification + email stub → webhook_logs + audit trail.
 */
class WebhookAutomationIT extends BaseIT {

    @Autowired private LeadRepository leadRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private WebhookLogRepository webhookLogRepository;
    @Autowired private AuditLogRepository auditLogRepository;
    @Autowired private AppProperties props;

    @Test
    void signedWebhookCreatesLeadRoundsRobinAndSchedulesAutomation() throws Exception {
        UUID raviId = createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");
        createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");

        Map<String, Object> body = body("Webhook Visitor", "9812345677");
        mockMvc.perform(post("/api/webhook/lead")
                        .header(WebhookSignature.HEADER, signature(body))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.leadId").isNotEmpty())
                .andExpect(jsonPath("$.ownerName").value("Ravi"));

        Lead lead = leadRepository.findAll().get(0);
        assertThat(lead.getCustomerName()).isEqualTo("Webhook Visitor");
        assertThat(lead.getMobileDigits()).isEqualTo("9812345677");
        assertThat(lead.getSource()).isEqualTo(Lead.Source.WEBSITE);
        assertThat(lead.getOwnerId()).isEqualTo(raviId);
        assertThat(lead.isConsentGiven()).isTrue();
        assertThat(lead.getRemarks()).isEqualTo("Website lead via webhook");
        assertThat(lead.getStatus()).isEqualTo(Lead.Status.NEW);
        assertThat(lead.getCreatedBy()).isNull(); // system actor

        Task task = taskRepository.findByLeadIdOrderByDueAtAsc(lead.getId()).get(0);
        assertThat(task.getType()).isEqualTo(Task.Type.INITIAL_CALL);
        assertThat(task.getStatus()).isEqualTo(Task.Status.PENDING);
        assertThat(task.getAssigneeId()).isEqualTo(raviId);

        assertThat(notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(raviId))
                .singleElement()
                .satisfies(n -> {
                    assertThat(n.getChannel()).isEqualTo(Notification.Channel.IN_APP);
                    assertThat(n.getTitle()).isEqualTo("Call within 5 minutes");
                    assertThat(n.getLink()).isEqualTo("/leads/" + lead.getId());
                });

        assertThat(webhookLogRepository.findAll())
                .singleElement()
                .satisfies(log -> {
                    assertThat(log.getStatus()).isEqualTo(WebhookLog.Status.success);
                    assertThat(log.getLeadId()).isEqualTo(lead.getId());
                    assertThat(log.getSource()).isEqualTo("WEBSITE");
                });

        assertThat(auditLogRepository.findAllByEntityAndEntityIdOrderBySeqDesc("LEAD", lead.getId()))
                .anyMatch(a -> a.getAction() == AuditAction.CREATE && a.getActorId() == null);
    }

    @Test
    void subsequentWebhookFlipsToNextSalesInRoundRobin() throws Exception {
        createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");
        UUID meeraId = createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");

        mockMvc.perform(signed("Visitor One", "9812345678"))
                .andExpect(status().isCreated());

        mockMvc.perform(signed("Visitor Two", "9812345679"))
                .andExpect(status().isCreated());

        List<Lead> leads = leadRepository.findAll().stream()
                .sorted(Comparator.comparing(Lead::getCreatedAt).thenComparing(Lead::getId))
                .toList();
        Lead first = leads.get(0);
        Lead second = leads.get(1);
        assertThat(first.getOwnerId()).isNotEqualTo(meeraId);
        assertThat(second.getOwnerId()).isEqualTo(meeraId);
    }

    @Test
    void duplicateWebhookIsSoftlyRejectedWithExistingLead() throws Exception {
        createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");

        Map<String, Object> body = body("Repeat Visitor", "9812345666");
        mockMvc.perform(signed(body)).andExpect(status().isCreated());

        mockMvc.perform(signed(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.leadId").isNotEmpty());

        assertThat(leadRepository.findAll()).hasSize(1);
        assertThat(webhookLogRepository.findAll())
                .extracting(WebhookLog::getStatus)
                .containsExactly(WebhookLog.Status.success, WebhookLog.Status.duplicate);
    }

    @Test
    void missingOrInvalidSignatureReturnsUnauthorized() throws Exception {
        String payload = json(body("Sneaky", "9812345665"));

        mockMvc.perform(post("/api/webhook/lead")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_SIGNATURE"));

        mockMvc.perform(post("/api/webhook/lead")
                        .header(WebhookSignature.HEADER, "sha256=" + "0".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isUnauthorized());

        assertThat(webhookLogRepository.findAll()).hasSize(2)
                .allSatisfy(log -> assertThat(log.getStatus()).isEqualTo(WebhookLog.Status.failed));
    }

    @Test
    void invalidPayloadsAreRejectedWithBadRequest() throws Exception {
        createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");

        // No customer name.
        mockMvc.perform(signed(body(null, "9812345664")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // Bad mobile number.
        mockMvc.perform(signed(body("Bad Mobile", "12345")))
                .andExpect(status().isBadRequest());

        // DPDPA consent withheld.
        Map<String, Object> noConsent = body("No Consent", "9812345663");
        noConsent.put("consent_given", false);
        mockMvc.perform(signed(noConsent))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Explicit consent (consent_given=true) is required to create a lead"));

        // Field-length / range constraints must mirror bean validation (400, not a DB 500).
        Map<String, Object> longName = body("A".repeat(201), "9812345662");
        mockMvc.perform(signed(longName)).andExpect(status().isBadRequest());

        Map<String, Object> manyPersons = body("Too Many", "9812345661");
        manyPersons.put("num_persons", 51);
        mockMvc.perform(signed(manyPersons)).andExpect(status().isBadRequest());

        Map<String, Object> negativeBudget = body("Negative Budget", "9812345660");
        negativeBudget.put("budget", -500);
        mockMvc.perform(signed(negativeBudget)).andExpect(status().isBadRequest());

        Map<String, Object> badBudgetScale = body("Precise Budget", "9812345659");
        badBudgetScale.put("budget", 123.456);
        mockMvc.perform(signed(badBudgetScale)).andExpect(status().isBadRequest());

        assertThat(webhookLogRepository.findAll())
                .hasSize(7)
                .allSatisfy(log -> assertThat(log.getStatus()).isEqualTo(WebhookLog.Status.failed));
    }

    @Test
    void oversizedPayloadIsRejected() throws Exception {
        createUser("sales.ravi@securetravels.in", "Ravi", Role.SALES, "sales123");

        String huge = "A".repeat(20 * 1024);
        Map<String, Object> big = body(huge, "9812345658");
        mockMvc.perform(signed(big))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("exceeds")));

        // Oversized payloads must not be stored in full.
        assertThat(webhookLogRepository.findAll()).isEmpty();
    }

    @Test
    void noSalesOrManagerReturnsServiceUnavailable() throws Exception {
        createUser("ops.one@securetravels.in", "Ops One", Role.OPS, "ops123");

        mockMvc.perform(signed(body("Alone", "9812345661")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        assertThat(webhookLogRepository.findAll())
                .singleElement()
                .satisfies(log -> assertThat(log.getStatus()).isEqualTo(WebhookLog.Status.failed));
    }

    private Map<String, Object> body(String name, String mobile) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customer_name", name);
        map.put("mobile_number", mobile);
        map.put("destination", "Kedarnath");
        map.put("num_persons", 2);
        map.put("message", "Website lead via webhook");
        map.put("consent_given", true);
        return map;
    }

    private String json(Map<String, Object> map) throws Exception {
        return objectMapper.writeValueAsString(map);
    }

    private String signature(Map<String, Object> body) throws Exception {
        return "sha256=" + WebhookSignature.compute(props.getWebhook().getSecret(),
                json(body).getBytes(StandardCharsets.UTF_8));
    }

    private MockHttpServletRequestBuilder signed(Map<String, Object> body) throws Exception {
        String payload = json(body);
        return post("/api/webhook/lead")
                .header(WebhookSignature.HEADER,
                        "sha256=" + WebhookSignature.compute(props.getWebhook().getSecret(),
                                payload.getBytes(StandardCharsets.UTF_8)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload);
    }

    private MockHttpServletRequestBuilder signed(String name, String mobile) throws Exception {
        return signed(body(name, mobile));
    }
}