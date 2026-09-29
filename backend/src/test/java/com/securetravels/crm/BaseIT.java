package com.securetravels.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Shared infra: a real Spring context hitting the local Postgres test
 * database (securetravels_test), tables truncated between tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class BaseIT {

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected UserRepository userRepository;
    @Autowired protected PasswordEncoder passwordEncoder;
    @Autowired protected JdbcTemplate jdbcTemplate;

    @BeforeEach
    void truncateAll() {
        jdbcTemplate.execute("""
TRUNCATE audit_log, leads, customer360, trips, vendors, batches, bookings,
                         travellers, seat_holds, payments, operations_handoffs, refresh_tokens,
                         users, tasks, notifications, sales_targets, webhook_logs, assignment_state,
                         documents, traveller_checklists,
                         timeline_events, whatsapp_messages, sales_commission_ledger,
consent_records, consent_suppressions,
                 email_messages, sms_messages, inbound_messages, communication_threads,
                 workflows, workflow_versions,
                 automation_events, workflow_runs, workflow_run_steps,
                 workflow_scheduled_steps, workflow_step_effects, workflow_run_failures
                  RESTART IDENTITY CASCADE""");
        // NB: whatsapp_templates is deliberately NOT truncated. It is reference
        // data seeded by V12; emptying it would make every outbound-send test
        // fail on "no enabled template" rather than on the behaviour under test.
        // channel_templates IS seeded by V15 with the same reference data, so it
        // is left alone for the same reason -- but unlike whatsapp_templates a
        // test may add rows to it, and truncateTemplateOverrides() clears only
        // what a test added.
        //
        // It does need resetting, though: `enabled` is a real operational
        // toggle (a test that disables a template would disable it for every
        // test afterwards) and approval_status is Phase 5 (a test that rejects
        // a template must not ghost the reference data either).
        jdbcTemplate.update("""
                UPDATE whatsapp_templates
                   SET enabled = true,
                       approval_status = 'APPROVED'
                 WHERE enabled = false OR approval_status != 'APPROVED'""");

        // Same two columns, for the V15 cross-channel library. Reset rather than
        // truncate, because V15 seeds it with the same reference data.
        jdbcTemplate.update("""
                UPDATE channel_templates
                   SET enabled = true,
                       approval_status = 'APPROVED'
                 WHERE enabled = false OR approval_status != 'APPROVED'""");
    }

    /**
     * Remove template rows a test created, leaving the V15 seed intact.
     *
     * <p>Needed because {@code channel_templates} holds real reference data
     * that most tests depend on, so it cannot simply be truncated. A test that
     * invents a template code should call this, or that code will still be here
     * for the next test class — where an "unknown template is rejected" test
     * would fail for the wrong reason.
     */
    protected void deleteTemplatesNotSeededByV15() {
        jdbcTemplate.update("""
                DELETE FROM channel_templates
                 WHERE (channel, code) NOT IN (
                       ('EMAIL','PACKAGE_DETAILS'), ('EMAIL','ITINERARY'),
                       ('EMAIL','PRICE_DETAILS'),   ('EMAIL','PAYMENT_LINK'),
                       ('EMAIL','TRIP_LOGISTICS'),  ('EMAIL','BOOKING_CONFIRMED'),
                       ('EMAIL','BALANCE_DUE'),     ('EMAIL','DOCUMENT_LINK'),
                       ('EMAIL','POST_TRIP_REVIEW'),
                       ('SMS','PACKAGE_DETAILS'),   ('SMS','BOOKING_CONFIRMED'),
                       ('SMS','BALANCE_DUE'),       ('SMS','DOCUMENT_LINK'))""");
    }

    protected UUID createUser(String email, String name, Role role, String password) {
        User user = new User(email, passwordEncoder.encode(password), name, role, "9876500000");
        return userRepository.save(user).getId();
    }

    protected String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    protected String authHeader(String token) {
        return "Bearer " + token;
    }
}