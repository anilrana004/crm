package com.securetravels.crm.lead;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LeadSliceIT extends BaseIT {

    @Test
    void createLeadValidatesAndScores() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        // source WEBSITE + 4 persons => HOT; consent echo; SSE sanitized on create
        Map<String, Object> payload = validLeadMap("Ravi Verma<script>alert(1)</script>", "9876500001", "WEBSITE", 4);
        payload.put("remarks", "<b>Urgent</b> & followup needed");

        String body = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Ravi Verma"))
                .andExpect(jsonPath("$.heat").value("HOT"))
                .andExpect(jsonPath("$.consentGiven").value(true))
                .andExpect(jsonPath("$.remarks").value("Urgent & followup needed"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("id").asText()).isNotNull();
    }

    @Test
    void consentIsMandatory() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        Map<String, Object> noConsent = validLeadMap("Amit", "9876500002", "WHATSAPP", 2);
        noConsent.put("consentGiven", false);

        mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(noConsent)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    void invalidMobileRejected() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        Map<String, Object> bad = validLeadMap("Amit", "12345", "WHATSAPP", 1);
        mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void duplicatePhoneRejected() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        createLead(token, validLead("First", "9876500003", "WHATSAPP", 2));
        mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLeadMap("Second", "+91 98765 00003", "WEBSITE", 2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void salesSeesOnlyOwnLeadsManagerSeesAll() throws Exception {
        UUID ravi = createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        String raviToken = login("sales@securetravels.in", "sales123");
        String meeraToken = login("sales.meera@securetravels.in", "sales123");
        String managerToken = login("manager@securetravels.in", "manager123");

        String a = createLead(raviToken, validLead("Ravi Lead", "9876500004", "WHATSAPP", 1));
        String b = createLead(meeraToken, validLead("Meera Lead", "9876500005", "WHATSAPP", 1));

        // Meera sees only her own
        mockMvc.perform(get("/api/leads").header("Authorization", authHeader(meeraToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerName").value("Meera Lead"));

        // manager sees both
        mockMvc.perform(get("/api/leads").header("Authorization", authHeader(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void statusFlowIsEnforcedAndAudited() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, validLead("Vikram", "9876500006", "WEBSITE", 1));

        // valid transition
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INTERESTED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERESTED"));

        // invalid: NEW leads cannot jump to BOOKING_CONFIRMED; INTERESTED cannot -> BOOKING_CONFIRMED directly
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "BOOKING_CONFIRMED"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        // LOST without reason rejected
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOST"))))
                .andExpect(status().isBadRequest());

        // LOST with reason accepted and audited (status reason only)
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "LOST", "lostReason", "POSTPONED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOST"))
                .andExpect(jsonPath("$.heat").value("COLD"));

        Integer statusRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where entity='LEAD' and entity_id=? " +
                        "and action='STATUS_CHANGE' and field='status'", Integer.class, UUID.fromString(id));
        Integer reasonRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where entity='LEAD' and entity_id=? and field='lost_reason'",
                Integer.class, UUID.fromString(id));
        assertThat(statusRows).isEqualTo(1);   // the successful change only
        assertThat(reasonRows).isEqualTo(1);
    }

    @Test
    void salesCannotManageOthersLeads() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");
        String raviToken = login("sales@securetravels.in", "sales123");
        String meeraToken = login("sales.meera@securetravels.in", "sales123");

        String id = createLead(raviToken, validLead("Ravi Lead", "9876500007", "WEBSITE", 2));

        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(meeraToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("status", "INTERESTED"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void editLeadRecomputesHeatAndAuditsChangedFields() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        // WHATSAPP, 1 pax, far date, 15k budget => COLD
        String id = createLead(token, validLead("Amit", "9876500008", "WHATSAPP", 1));

        String body = mockMvc.perform(patch("/api/leads/" + id)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "budget", 100000, "numPersons", 4))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heat").value("HOT"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(body).get("budget").decimalValue()).isEqualByComparingTo("100000");

        Integer budgetRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where entity='LEAD' and entity_id=? and field='budget'",
                Integer.class, UUID.fromString(id));
        Integer personsRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where entity='LEAD' and entity_id=? and field='numPersons'",
                Integer.class, UUID.fromString(id));
        assertThat(budgetRows).isEqualTo(1);
        assertThat(personsRows).isEqualTo(1);
    }

    @Test
    void returningCustomerLeadIsLinkedAndLoggedAsNewTripInterest() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        jdbcTemplate.execute("""
                INSERT INTO customer360 (full_name, mobile_number, mobile_digits, consent_given)
                VALUES ('Priya Returner', '+91 98765 00009', '9876500009', true)
                """);

        String body = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validLeadMap("Priya", "+91 98765 00009", "WEBSITE", 2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer360Id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String customerId = objectMapper.readTree(body).get("customer360Id").asText();

        Integer interestRows = jdbcTemplate.queryForObject(
                "select count(*) from audit_log where entity='CUSTOMER360' and entity_id=? " +
                        "and field='new_trip_interest'", Integer.class, UUID.fromString(customerId));
        assertThat(interestRows).isEqualTo(1);
    }

    @Test
    void listFiltersByTripAndHeat() throws Exception {
        createUser("sales.meera@securetravels.in", "Meera", Role.SALES, "sales123");
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        String meeraToken = login("sales.meera@securetravels.in", "sales123");
        String managerToken = login("manager@securetravels.in", "manager123");

        UUID tripId = jdbcTemplate.queryForObject("""
                INSERT INTO trips (name, slug, category, booking_type, base_cost, duration_days,
                                   itinerary, inclusions, exclusions)
                VALUES ('Test Trek', 'test-trek', 'TREK', 'FIXED_BATCH', 5000, 3, 'it', 'in', 'ex')
                RETURNING id""", UUID.class);

        Map<String, Object> hotOnTrip = validLeadMap("Hot Trip", "9876500010", "WEBSITE", 4);
        hotOnTrip.put("tripId", tripId.toString());
        Map<String, Object> coldNoTrip = validLeadMap("Cold No Trip", "9876500011", "WHATSAPP", 1);
        coldNoTrip.put("budget", 500);
        coldNoTrip.put("numPersons", 1);

        createLead(meeraToken, hotOnTrip);
        createLead(meeraToken, coldNoTrip);

        // trip filter narrows to the one lead carrying that trip
        mockMvc.perform(get("/api/leads").param("tripId", tripId.toString())
                        .header("Authorization", authHeader(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerName").value("Hot Trip"));

        // heat filter: only the 4-pax WEBSITE lead is HOT
        mockMvc.perform(get("/api/leads").param("heat", "HOT")
                        .header("Authorization", authHeader(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].customerName").value("Hot Trip"));

        // combined: trip + heat still resolves the same single lead
        mockMvc.perform(get("/api/leads").param("tripId", tripId.toString()).param("heat", "COLD")
                        .header("Authorization", authHeader(managerToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void getLeadDetailAndActivityTimeline() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");
        String id = createLead(token, validLead("Vikram", "9876500012", "WEBSITE", 2));

        mockMvc.perform(get("/api/leads/" + id).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerName").value("Vikram"));

        // create + note + status change appear in the timeline, newest first
        mockMvc.perform(patch("/api/leads/" + id + "/status")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "status", "INTERESTED", "note", "Left a voicemail, will call back"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/leads/" + id + "/activity")
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].field").value("note"))
                .andExpect(jsonPath("$[1].action").value("STATUS_CHANGE"))
                .andExpect(jsonPath("$[2].action").value("CREATE"));
    }

    private String createLead(String token, Map<String, Object> payload) throws Exception {
        String body = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private Map<String, Object> validLead(String name, String mobile, String source, int persons) {
        Map<String, Object> map = validLeadMap(name, mobile, source, persons);
        return map;
    }

    private Map<String, Object> validLeadMap(String name, String mobile, String source, int persons) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("customerName", name);
        map.put("mobileNumber", mobile);
        map.put("whatsappNumber", mobile);
        map.put("source", source);
        map.put("destination", "Kedarnath");
        map.put("numPersons", persons);
        map.put("budget", 15000);
        map.put("travelDate", "2026-12-24");
        map.put("consentGiven", true);
        return map;
    }
}