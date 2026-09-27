package com.securetravels.crm.document;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ComplianceFlowIT extends BaseIT {

    @Autowired private SignedUploadUrlService signer;

    @Test
    void fullDocumentComplianceGateFlow() throws Exception {
        String token = managerToken();

        // --- Setup: trip (DIFFICULT) + batch + confirmed booking with adult + minor.
        UUID tripId = createTrip(token, "Kedarnath-IT", "FIXED_BATCH", "DIFFICULT");
        UUID batchId = createBatch(token, tripId, "2026-12-01", 10);
        UUID customerId = createCustomer();
        UUID bookingId = createBooking(customerId, tripId, batchId, token);
        confirmBooking(bookingId, token);

        // Traveller IDs from the booking (need direct access — DB query).
        UUID adultId = jdbcTemplate.queryForObject(
                "SELECT id FROM travellers WHERE full_name='Adult Person' ORDER BY created_at LIMIT 1", UUID.class);
        UUID minorId = jdbcTemplate.queryForObject(
                "SELECT id FROM travellers WHERE full_name='Minor Person' ORDER BY created_at LIMIT 1", UUID.class);

        // --- Board before any compliance.
        mockMvc.perform(get("/api/compliance/batches/{id}", batchId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliancePercent").value(0))
                .andExpect(jsonPath("$.color").value("RED"))
                .andExpect(jsonPath("$.readyForDeparture").value(false));

        // --- Upload URL + confirm for adult ID proof.
        String uploadBody = objectMapper.writeValueAsString(
                java.util.Map.of("travellerId", adultId.toString(), "docType", "ID_PROOF"));
        String uploadResp = mockMvc.perform(post("/api/documents/upload-url")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(uploadBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String storageKey = objectMapper.readTree(uploadResp).get("storageKey").asText();
        assertThat(storageKey).startsWith("compliance/" + adultId + "/");

        String confirmBody = objectMapper.writeValueAsString(java.util.Map.of(
                "storageKey", storageKey,
                "relatedType", "TRAVELLER",
                "docType", "ID_PROOF",
                "travellerId", adultId.toString(),
                "mimeType", "application/pdf",
                "sizeBytes", 1024));
        mockMvc.perform(post("/api/documents/confirm")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storageKey").value(storageKey));

        // Document appears on traveller list.
        mockMvc.perform(get("/api/documents").param("travellerId", adultId.toString())
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].docType").value("ID_PROOF"));

        // --- Board now shows ID proof as IN_PROGRESS (yellow), rest RED.
        mockMvc.perform(get("/api/compliance/batches/{id}", batchId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliancePercent").value(0));

        // --- Mark checklist: adult ID_PROOF VERIFIED, emergency contact, medical VERIFIED,
        //     minor consent VERIFIED (adult medical + minor consent required on DIFFICULT).
        String markAdultBody = objectMapper.writeValueAsString(java.util.Map.of(
                "items", java.util.List.of(
                        java.util.Map.of("item", "ID_PROOF", "status", "VERIFIED"),
                        java.util.Map.of("item", "MEDICAL_FITNESS", "status", "VERIFIED"),
                        java.util.Map.of("item", "EMERGENCY_CONTACT", "status", "VERIFIED")),
                "emergencyContactName", "Family Contact",
                "emergencyContactPhone", "+919800099999"));
        mockMvc.perform(put("/api/compliance/travellers/{id}", adultId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markAdultBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requiredCount").value(3))
                .andExpect(jsonPath("$.items[0].status").value("VERIFIED"))
                .andExpect(jsonPath("$.items[1].status").value("VERIFIED"))
                .andExpect(jsonPath("$.items[2].status").value("VERIFIED"))
                .andExpect(jsonPath("$.color").value("GREEN"));

        String markMinorBody = objectMapper.writeValueAsString(java.util.Map.of(
                "items", java.util.List.of(
                        java.util.Map.of("item", "ID_PROOF", "status", "VERIFIED"),
                        java.util.Map.of("item", "MEDICAL_FITNESS", "status", "VERIFIED"),
                        java.util.Map.of("item", "EMERGENCY_CONTACT", "status", "VERIFIED"),
                        java.util.Map.of("item", "MINOR_CONSENT", "status", "VERIFIED")),
                "emergencyContactName", "Minor Guardian",
                "emergencyContactPhone", "+919800088888"));
        mockMvc.perform(put("/api/compliance/travellers/{id}", minorId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(markMinorBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compliancePercent").value(100));

        // --- Batch now 100% and gate passes.
        mockMvc.perform(get("/api/compliance/batches/{id}", batchId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readyForDeparture").value(true));

        mockMvc.perform(post("/api/compliance/batches/{id}/ready-for-departure", batchId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(batchId.toString()));

        // Batch is now READY_FOR_DEPARTURE.
        mockMvc.perform(get("/api/trips/{tripId}", tripId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].status").value("READY_FOR_DEPARTURE"));

        // --- Second batch with incomplete compliance → gate 409.
        UUID batch2 = createBatch(token, tripId, "2026-12-08", 8);
        UUID cust2 = createCustomer();
        UUID booking2 = createBooking(cust2, tripId, batch2, token);
        confirmBooking(booking2, token);

        mockMvc.perform(post("/api/compliance/batches/{id}/ready-for-departure", batch2)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isConflict());

        // Patch path also fails.
        mockMvc.perform(patch("/api/batches/{id}", batch2)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"READY_FOR_DEPARTURE\"}"))
                .andExpect(status().isBadRequest());

        // --- Verify expired presigned URL is rejected at the verification layer (unit tests
        //     already prove full SigV4 expiry check; validate the path through the real bean).
        String key = "compliance/" + adultId + "/202609/" + UUID.randomUUID() + "-MEDICAL_CERT";
        String url = signer.issue(key, java.time.Instant.now(), java.time.Duration.ofSeconds(1));
        assertThat(signer.verify(url, "PUT", java.time.Instant.now().plusSeconds(2)).valid()).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private UUID createTrip(String token, String name, String bookingType, String difficulty) throws Exception {
        String resp = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "name", name, "category", "PILGRIMAGE",
                                "bookingType", bookingType, "baseCost", 12000,
                                "durationDays", 5, "difficulty", difficulty))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("id").asText());
    }

    private UUID createBatch(String token, UUID tripId, String date, int cap) throws Exception {
        String resp = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "departureDate", date, "maxCapacity", cap))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("id").asText());
    }

    private UUID createCustomer() {
        String mobile = "+9198" + (980000000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000L));
        return jdbcTemplate.queryForObject(
                "INSERT INTO customer360 (id, full_name, mobile_number, mobile_digits, consent_given) "
                        + "VALUES (gen_random_uuid(), 'Test Customer', '" + mobile + "', "
                        + "'" + mobile.substring(3) + "', true) RETURNING id",
                UUID.class);
    }

    private UUID createBooking(UUID customerId, UUID tripId, UUID batchId, String token) throws Exception {
        String resp = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "customerId", customerId.toString(),
                                "tripId", tripId.toString(),
                                "batchId", batchId.toString(),
                                "numTravellers", 2,
                                "travelDate", "2026-12-01",
                                "travellers", java.util.List.of(
                                        java.util.Map.of("fullName", "Adult Person", "age", 30,
                                                "gender", "M", "medicalCertRequired", false,
                                                "phone", "9800011111"),
                                        java.util.Map.of("fullName", "Minor Person", "age", 15,
                                                "gender", "M", "medicalCertRequired", false,
                                                "phone", "9800022222"))))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(resp).get("id").asText());
    }

    private void confirmBooking(UUID bookingId, String token) throws Exception {
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CONFIRMED\",\"note\":\"test confirm\"}"))
                .andExpect(status().isOk());
    }
}