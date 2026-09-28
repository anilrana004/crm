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
                         timeline_events, whatsapp_messages, sales_commission_ledger
                RESTART IDENTITY CASCADE""");
        // NB: whatsapp_templates is deliberately NOT truncated. It is reference
        // data seeded by V12; emptying it would make every outbound-send test
        // fail on "no enabled template" rather than on the behaviour under test.
        //
        // It does need resetting, though: `enabled` is a real operational
        // toggle, so a test that disables a template to exercise a failure path
        // would otherwise disable it for every test that runs afterwards.
        jdbcTemplate.update("UPDATE whatsapp_templates SET enabled = true WHERE enabled = false");
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