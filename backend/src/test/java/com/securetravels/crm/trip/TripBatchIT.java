package com.securetravels.crm.trip;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class TripBatchIT extends BaseIT {

    @Autowired private TripRepository tripRepository;
    @Autowired private BatchRepository batchRepository;

    @Test
    void managerCreatesTripWithAutoSlugAndCollisionSuffixesSecond() throws Exception {
        String token = managerToken();

        String first = createTrip(token, "Kedarnath Yatra");
        String second = createTrip(token, "Kedarnath Yatra");

        assertThat(tripRepository.findBySlug("kedarnath-yatra")).isPresent();
        assertThat(tripRepository.findBySlug("kedarnath-yatra-2")).isPresent();

        mockMvc.perform(get("/api/trips/{id}", first).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("kedarnath-yatra"))
                .andExpect(jsonPath("$.category").value("PILGRIMAGE"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(get("/api/trips/{id}", second).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("kedarnath-yatra-2"));
    }

    @Test
    void salesCannotManageTripCatalogue() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        String token = login("sales@securetravels.in", "sales123");

        mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tripPayload("Forbidden Trip"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void tripDetailIncludesBatchesAndActiveFilterExcludesDeactivated() throws Exception {
        String token = managerToken();

        String tripId = createTrip(token, "Char Dham");
        String batchId = createBatch(token, tripId, "2026-06-15", 24);

        mockMvc.perform(get("/api/trips/{id}", tripId).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].departureDate").value("2026-06-15"))
                .andExpect(jsonPath("$.batches[0].maxCapacity").value(24))
                .andExpect(jsonPath("$.batches[0].available").value(24))
                .andExpect(jsonPath("$.batches[0].status").value("OPEN"));

        mockMvc.perform(get("/api/trips").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + tripId + "')]").exists());

        mockMvc.perform(patch("/api/trips/{id}", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/trips").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + tripId + "')]").doesNotExist());

        mockMvc.perform(get("/api/trips").param("active", "false").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + tripId + "')]").exists());
    }

    @Test
    void batchCreateValidatesBookingTypeAndDuplicateDeparture() throws Exception {
        String token = managerToken();

        String fixedTrip = createTrip(token, "Kashmir");
        String customTrip = createTrip(token, "Custom Honeymoon", "CUSTOM_FIT");

        mockMvc.perform(post("/api/trips/{tripId}/batches", customTrip)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchPayload("2026-08-01", 10))))
                .andExpect(status().isBadRequest());

        createBatch(token, fixedTrip, "2026-08-01", 10);

        mockMvc.perform(post("/api/trips/{tripId}/batches", fixedTrip)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batchPayload("2026-08-01", 10))))
                .andExpect(status().isConflict());
    }

    @Test
    void batchUpdateGuardsCapacityAndBlocksChangesToCancelled() throws Exception {
        String token = managerToken();
        String tripId = createTrip(token, "Rim of Ladakh");
        String batchId = createBatch(token, tripId, "2026-09-01", 20);

        // shrink below a booked seat count -> 400 (simulate a confirmed seat on the pool)
        Batch batch = batchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        batch.setSeatsBooked(5);
        batchRepository.save(batch);

        mockMvc.perform(patch("/api/batches/{id}", batchId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxCapacity\": 3}"))
                .andExpect(status().isBadRequest());

        // shrink to a legal size and open a second batch on the same trip
        mockMvc.perform(patch("/api/batches/{id}", batchId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"maxCapacity\": 8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatsBooked").value(5))
                .andExpect(jsonPath("$.available").value(3));

        // duplicate departure via move -> 409
        String secondBatch = createBatch(token, tripId, "2026-09-10", 20);
        mockMvc.perform(patch("/api/batches/{id}", secondBatch)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"departureDate\": \"2026-09-01\"}"))
                .andExpect(status().isConflict());

        // CANCELLED is terminal
        mockMvc.perform(patch("/api/batches/{id}", secondBatch)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/batches/{id}", secondBatch)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void guideVendorLifecycleAndBatchAssignment() throws Exception {
        String token = managerToken();

        String guideId = createGuideVendor(token, "Suresh Sharma", "9876507777");
        String tripId = createTrip(token, "Valley of Flowers");
        String batchId = createBatch(token, tripId, "2026-07-20", 16, guideId);

        mockMvc.perform(get("/api/trips/{id}", tripId).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batches[0].guideId").value(guideId))
                .andExpect(jsonPath("$.batches[0].guideName").value("Suresh Sharma"));

        mockMvc.perform(get("/api/vendors?category=GUIDE").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + guideId + "')]").exists());

        mockMvc.perform(patch("/api/vendors/{id}", guideId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(get("/api/vendors?category=GUIDE").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + guideId + "')]").doesNotExist());
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String createTrip(String token, String name) throws Exception {
        return createTrip(token, name, "FIXED_BATCH");
    }

    private String createTrip(String token, String name, String bookingType) throws Exception {
        Map<String, Object> body = tripPayload(name);
        body.put("bookingType", bookingType);
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private Map<String, Object> tripPayload(String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", "PILGRIMAGE");
        body.put("bookingType", "FIXED_BATCH");
        body.put("baseCost", 25000);
        body.put("durationDays", 6);
        return body;
    }

    private String createBatch(String token, String tripId, String departureDate, int capacity) throws Exception {
        return createBatch(token, tripId, departureDate, capacity, null);
    }

    private String createBatch(String token, String tripId, String departureDate, int capacity,
                               String guideId) throws Exception {
        Map<String, Object> body = batchPayload(departureDate, capacity);
        if (guideId != null) {
            body.put("guideId", guideId);
        }
        String created = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private Map<String, Object> batchPayload(String departureDate, int capacity) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("departureDate", departureDate);
        body.put("maxCapacity", capacity);
        return body;
    }

    private String createGuideVendor(String token, String name, String phone) throws Exception {
        String created = mockMvc.perform(post("/api/vendors")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "GUIDE", "name", name, "phone", phone, "dailyRate", 4500))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }
}