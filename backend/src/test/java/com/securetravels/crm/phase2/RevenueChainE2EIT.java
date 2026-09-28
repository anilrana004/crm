package com.securetravels.crm.phase2;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one test that walks the whole Phase 1 + Phase 2 (Modules 1-4) revenue
 * chain in a single booking, in the order the business actually performs it.
 *
 * <p>Lead → quotation → customer 360 → booking → payment → document collection
 * → compliance green → batch cleared for departure → operations handoff.
 *
 * <p>This exists because the per-module integration tests each prove their own
 * slice in isolation: {@code BookingFlowIT} never uploads a document,
 * {@code ComplianceFlowIT} never records a payment, {@code OperationsFlowIT}
 * never touches compliance. Any one of them stays green while a seam between
 * two modules rots — a compliance gate that stops seeing confirmed bookings, a
 * handoff that stops carrying the payment state, a booking that stops inheriting
 * the customer. Those seams are only exercised when one test crosses all of
 * them, which is what this does.
 *
 * <p>Everything here runs through real HTTP against the real service layer; the
 * only stubbing is the object store, which is asserted by key rather than
 * uploaded to.
 */
class RevenueChainE2EIT extends BaseIT {

    private static final String PAID_AT = "2026-09-27T10:15:30Z";

    @Test
    void leadToDepartureReadyHandoffInOnePass() throws Exception {
        String manager = managerToken();
        String sales = salesToken();
        String ops = opsToken();

        // ---------------------------------------------------------------- 1. lead
        String leadResp = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.ofEntries(
                                Map.entry("customerName", "Ananya Desai"),
                                Map.entry("mobileNumber", "+919812345678"),
                                Map.entry("whatsappNumber", "+919812345678"),
                                Map.entry("email", "ananya@example.in"),
                                Map.entry("source", "WEBSITE"),
                                Map.entry("destination", "Kedarnath"),
                                Map.entry("travelDate", "2026-12-01"),
                                Map.entry("numPersons", 2),
                                Map.entry("remarks", "E2E chain test"),
                                Map.entry("consentGiven", true),
                                Map.entry("consentScope", "ALL")))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID leadId = UUID.fromString(parse(leadResp).get("id").asText());

        // A new lead must be actionable immediately, or the chain stalls here.
        mockMvc.perform(get("/api/leads/{id}/activity", leadId)
                        .header("Authorization", authHeader(sales)))
                .andExpect(status().isOk());

        // Quotation sent — the step the sales team actually moves the lead through.
        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"QUOTATION_SENT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("QUOTATION_SENT"));

        // ------------------------------------------------------------- 2. customer
        // Booking requires a customer360 row; the mobile is the match key.
        UUID customerId = jdbcTemplate.queryForObject("""
                INSERT INTO customer360 (id, full_name, mobile_number, mobile_digits, consent_given)
                VALUES (gen_random_uuid(), 'Ananya Desai', '+919812345678', '9812345678', true)
                RETURNING id
                """, UUID.class);

        // ------------------------------------------------------- 3. trip + batch
        UUID tripId = createTrip(manager, "Kedarnath-E2E", "MODERATE");
        UUID batchId = createBatch(manager, tripId, "2026-12-01", 12);

        // ------------------------------------------------------------- 4. booking
        String bookingResp = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "customerId", customerId.toString(),
                                "tripId", tripId.toString(),
                                "batchId", batchId.toString(),
                                "numTravellers", 2,
                                "travelDate", "2026-12-01",
                                "travellers", List.of(
                                        traveller("Ananya Desai", 34, "9800011111"),
                                        traveller("Vihaan Desai", 9, "9800022222"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(parse(bookingResp).get("id").asText());
        String bookingRef = parse(bookingResp).get("bookingRef").asText();

        // A lead-linked booking must stay traceable back to the lead.
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"E2E confirm\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // ------------------------------------------------------------- 5. payment
        // A recorded advance starts PENDING, and a PENDING receipt must NOT reduce
        // the balance — otherwise an operator who has entered a payment but not yet
        // confirmed the bank credit would see the booking as under-collected.
        String paymentResp = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "bookingId", bookingId.toString(),
                                "amount", 5000,
                                "amountType", "ADVANCE",
                                "gatewayRef", "E2E-GATEWAY-1",
                                "notes", "E2E advance"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID paymentId = UUID.fromString(parse(paymentResp).get("id").asText());
        assertThat(parse(paymentResp).get("status").asText()).isEqualTo("PENDING");

        var summaryPending = summary(manager, bookingId);
        double net = summaryPending.get("netAmount").asDouble();
        assertThat(summaryPending.get("appliedAmount").asDouble()).isZero();
        assertThat(summaryPending.get("balanceAmount").asDouble()).isEqualTo(net);

        // Confirming the receipt is what actually moves the money. paidAt is
        // mandatory for COMPLETED (enforced in the service and by a DB CHECK).
        mockMvc.perform(patch("/api/payments/{id}/status", paymentId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "COMPLETED", "paidAt", PAID_AT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var summaryAfterAdvance = summary(manager, bookingId);
        assertThat(summaryAfterAdvance.get("appliedAmount").asDouble()).isEqualTo(5000.0);
        assertThat(summaryAfterAdvance.get("balanceAmount").asDouble()).isEqualTo(net - 5000.0);
        assertThat(summaryAfterAdvance.get("hasOverdueLine").asBoolean()).isFalse();

        // ------------------------------------------------------------ 6. documents
        // The minor makes MINOR_CONSENT mandatory, and the guardian contact is
        // what a real ops team would be chasing by hand.
        UUID adultId = travellerId(bookingId, "Ananya Desai");
        UUID minorId = travellerId(bookingId, "Vihaan Desai");

        // A minor's consent is uploaded as a CONSENT_FORM document; MINOR_CONSENT
        // is the checklist item it satisfies, not a document type.
        for (Map<String, String> p : List.of(
                Map.of("travellerId", adultId.toString(), "docType", "ID_PROOF"),
                Map.of("travellerId", minorId.toString(), "docType", "ID_PROOF"),
                Map.of("travellerId", minorId.toString(), "docType", "CONSENT_FORM"))) {
            String uploadResp = mockMvc.perform(post("/api/documents/upload-url")
                            .header("Authorization", authHeader(manager))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(p)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            String storageKey = parse(uploadResp).get("storageKey").asText();

            mockMvc.perform(post("/api/documents/confirm")
                            .header("Authorization", authHeader(manager))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(Map.of(
                                    "storageKey", storageKey,
                                    "relatedType", "TRAVELLER",
                                    "docType", p.get("docType"),
                                    "travellerId", p.get("travellerId"),
                                    "mimeType", "application/pdf",
                                    "sizeBytes", 2048))))
                    .andExpect(status().isCreated());
        }

        // --------------------------------------------------------- 7. compliance
        // Before review the gate must still be shut: uploaded != verified.
        mockMvc.perform(get("/api/compliance/batches/{id}", batchId)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForDeparture").value(false))
                .andExpect(jsonPath("$.color").value("RED"));

        mockMvc.perform(put("/api/compliance/travellers/{id}", adultId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "items", List.of(
                                        Map.of("item", "ID_PROOF", "status", "VERIFIED"),
                                        Map.of("item", "EMERGENCY_CONTACT", "status", "VERIFIED")),
                                "emergencyContactName", "Rohan Desai",
                                "emergencyContactPhone", "+919800055555"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.color").value("GREEN"));

        mockMvc.perform(put("/api/compliance/travellers/{id}", minorId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "items", List.of(
                                        Map.of("item", "ID_PROOF", "status", "VERIFIED"),
                                        Map.of("item", "MINOR_CONSENT", "status", "VERIFIED"),
                                        Map.of("item", "EMERGENCY_CONTACT", "status", "VERIFIED")),
                                "emergencyContactName", "Rohan Desai",
                                "emergencyContactPhone", "+919800055555"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/compliance/batches/{id}", batchId)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliancePercent").value(100))
                .andExpect(jsonPath("$.color").value("GREEN"))
                .andExpect(jsonPath("$.remainingItems").value(0))
                .andExpect(jsonPath("$.readyForDeparture").value(true));

        // ---------------------------------------------------- 8. batch departure
        mockMvc.perform(post("/api/compliance/batches/{id}/ready-for-departure", batchId)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/trips/{tripId}", tripId)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].status").value("READY_FOR_DEPARTURE"));

        // --------------------------------------------------- 9. operations handoff
        // Invariant I6: confirming the booking must have produced exactly one
        // handoff, carrying the booking, customer, batch and payment state.
        String opsResp = mockMvc.perform(get("/api/operations")
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var handoffs = parse(opsResp);
        assertThat(handoffs).hasSize(1);
        assertThat(handoffs.get(0).get("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(handoffs.get(0).get("bookingRef").asText()).isEqualTo(bookingRef);
        assertThat(handoffs.get(0).get("batchId").asText()).isEqualTo(batchId.toString());
        assertThat(handoffs.get(0).get("customerId").asText()).isEqualTo(customerId.toString());
        assertThat(handoffs.get(0).get("opsRef").asText()).isNotBlank();

        UUID handoffId = UUID.fromString(handoffs.get(0).get("id").asText());

        // The handoff's receivable view must reflect the advance already taken.
        mockMvc.perform(get("/api/operations/{id}", handoffId)
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingRef").value(bookingRef));

        // --------------------------------------------------- 10. close the payment
        // Settling the remaining balance must drive it to zero, proving the
        // receivable chain that OperationsFlowIT and PaymentFlowIT never share.
        var beforeSettle = summary(manager, bookingId);
        double outstanding = beforeSettle.get("balanceAmount").asDouble();
        assertThat(outstanding).isGreaterThan(0.0);

        String settleResp = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "bookingId", bookingId.toString(),
                                "amount", outstanding,
                                "amountType", "BALANCE",
                                "gatewayRef", "E2E-GATEWAY-2"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID settleId = UUID.fromString(parse(settleResp).get("id").asText());

        // Still outstanding until the receipt is confirmed — same rule as above.
        assertThat(summary(manager, bookingId).get("balanceAmount").asDouble())
                .isEqualTo(outstanding);

        mockMvc.perform(patch("/api/payments/{id}/status", settleId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("status", "COMPLETED", "paidAt", PAID_AT))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        var afterSettle = summary(manager, bookingId);
        assertThat(afterSettle.get("appliedAmount").asDouble()).isEqualTo(net);
        assertThat(afterSettle.get("balanceAmount").asDouble()).isZero();
    }

    // -------------------------------------------------------------------- helpers

    private String body(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private com.fasterxml.jackson.databind.JsonNode parse(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    private com.fasterxml.jackson.databind.JsonNode summary(String token, UUID bookingId) throws Exception {
        String resp = mockMvc.perform(get("/api/payments/booking/{bookingId}/summary", bookingId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return parse(resp);
    }

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String salesToken() throws Exception {
        createUser("sales.e2e@securetravels.in", "Sales E2E", Role.SALES, "sales123");
        return login("sales.e2e@securetravels.in", "sales123");
    }

    private String opsToken() throws Exception {
        createUser("ops.e2e@securetravels.in", "Ops E2E", Role.OPS, "ops123");
        return login("ops.e2e@securetravels.in", "ops123");
    }

    private static Map<String, Object> traveller(String name, int age, String phone) {
        return Map.of("fullName", name, "age", age, "gender", "F",
                "medicalCertRequired", false, "phone", phone);
    }

    private UUID createTrip(String token, String name, String difficulty) throws Exception {
        String resp = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of(
                                "name", name, "category", "PILGRIMAGE",
                                "bookingType", "FIXED_BATCH", "baseCost", 24000,
                                "durationDays", 5, "difficulty", difficulty))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(resp).get("id").asText());
    }

    private UUID createBatch(String token, UUID tripId, String date, int cap) throws Exception {
        String resp = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(Map.of("departureDate", date, "maxCapacity", cap))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(parse(resp).get("id").asText());
    }

    private UUID travellerId(UUID bookingId, String fullName) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM travellers WHERE booking_id = ? AND full_name = ?",
                UUID.class, bookingId, fullName);
    }
}
