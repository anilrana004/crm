package com.securetravels.crm.analytics;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role boundaries on the Module 2 endpoints.
 *
 * <p>{@code @PreAuthorize} is the only thing standing between a consultant and
 * another consultant's revenue, and a mistaken role string fails OPEN, not
 * closed, so each boundary is asserted with a real login rather than a mock user.
 */
class AnalyticsAccessIT extends BaseIT {

    @Test
    @DisplayName("a SALES user cannot read the operations readiness report")
    void salesCannotReadOperations() throws Exception {
        createUser("scoped.sales@x.in", "Scoped Sales", Role.SALES, "sales123");
        String token = login("scoped.sales@x.in", "sales123");

        mockMvc.perform(get("/api/analytics/operations")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a SALES user cannot read the audit log")
    void salesCannotReadAudit() throws Exception {
        createUser("auditor.sales@x.in", "Auditor Sales", Role.SALES, "sales123");
        String token = login("auditor.sales@x.in", "sales123");

        // audit_log holds the old and new value of every mutation, including
        // customer contact details, so it is not a broad-role read.
        mockMvc.perform(get("/api/analytics/audit")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a MANAGER cannot read the audit log either")
    void managerCannotReadAudit() throws Exception {
        createUser("auditor.manager@x.in", "Auditor Manager", Role.MANAGER, "manager123");
        String token = login("auditor.manager@x.in", "manager123");

        mockMvc.perform(get("/api/analytics/audit")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an ADMIN can read the audit log")
    void adminCanReadAudit() throws Exception {
        createUser("auditor.admin@x.in", "Auditor Admin", Role.ADMIN, "admin123");
        String token = login("auditor.admin@x.in", "admin123");

        mockMvc.perform(get("/api/analytics/audit")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("STRUCTURED"));
    }

    @Test
    @DisplayName("an OPS user can read operations readiness and is told incidents are unavailable")
    void opsCanReadOperations() throws Exception {
        createUser("ready.ops@x.in", "Ready Ops", Role.OPS, "ops123");
        String token = login("ready.ops@x.in", "ops123");

        mockMvc.perform(get("/api/analytics/operations")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                // The unavailable state must survive serialisation to the client;
                // a default-constructed or null-omitted field would show the UI as
                // "no incidents" instead of "not tracked".
                .andExpect(jsonPath("$.incidentSummary.available").value(false))
                .andExpect(jsonPath("$.incidentSummary.openIncidents").doesNotExist());
    }

    @Test
    @DisplayName("a SALES user can read the team report, scoped to self")
    void salesCanReadTeamScoped() throws Exception {
        UUID other = createUser("other.sales@x.in", "Other Sales", Role.SALES, "sales123");
        UUID self = createUser("self.sales@x.in", "Self Sales", Role.SALES, "sales123");
        String token = login("self.sales@x.in", "sales123");

        // Give the colleague something worth seeing. Before the row-set fix,
        // the presence gates (exists leads / credits / tasks) decided the
        // rows, so this colleague was returned to a SALES caller that asked
        // about themselves.
        jdbcTemplate.update("""
                INSERT INTO leads (customer_name, mobile_number, mobile_digits, source, owner_id)
                VALUES ('Other Person', '9876500000', '9187650000', 'WEBSITE', ?)
                """, other);

        // Ask for a colleague explicitly; the response must still be
        // self-scoped and must NOT return the colleague's row.
        mockMvc.perform(get("/api/analytics/team")
                        .param("consultantId", other.toString())
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("SELF"))
                .andExpect(jsonPath("$.filter.consultantId").value(self.toString()))
                .andExpect(jsonPath("$.consultants.length()").value(0))
                .andExpect(jsonPath("$.consultants[?(@.consultantId == '" + other + "')]").isEmpty());
    }

    @Test
    @DisplayName("a MANAGER reading the team report is unscoped")
    void managerSeesAll() throws Exception {
        createUser("boss.manager@x.in", "Boss Manager", Role.MANAGER, "manager123");
        String token = login("boss.manager@x.in", "manager123");

        mockMvc.perform(get("/api/analytics/team")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("ALL"))
                .andExpect(jsonPath("$.filter.consultantId").doesNotExist());
    }

    @Test
    @DisplayName("an unauthenticated request is rejected")
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(get("/api/analytics/funnel"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a season filter value that is not a season is a bad request, not a silent all-seasons")
    void invalidSeasonIsRejected() throws Exception {
        createUser("season.user@x.in", "Season User", Role.MANAGER, "manager123");
        String token = login("season.user@x.in", "manager123");

        mockMvc.perform(get("/api/analytics/funnel")
                        .param("season", "WINTER")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/analytics/funnel")
                        .param("season", "SUMMER")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().is4xxClientError());
    }

}
