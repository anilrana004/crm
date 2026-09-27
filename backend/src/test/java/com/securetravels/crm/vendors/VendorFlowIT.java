package com.securetravels.crm.vendors;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class VendorFlowIT extends BaseIT {

    @Test
    void vendorCatalogueCrudAndCategoryFilter() throws Exception {
        String token = managerToken();

        String hotelId = createVendor(token, "HOTEL", "Shivalik Inn", "9876500101", "22GSPFM0000L1Z5");
        String driverId = createVendor(token, "DRIVER", "Ramesh Kumar", "9876500102", null);

        // Category filter.
        mockMvc.perform(get("/api/vendors?category=HOTEL").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + hotelId + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + driverId + "')]").doesNotExist());

        // GET detail.
        mockMvc.perform(get("/api/vendors/{id}", hotelId).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.category").value("HOTEL"))
                .andExpect(jsonPath("$.name").value("Shivalik Inn"))
                .andExpect(jsonPath("$.gstin").value("22GSPFM0000L1Z5"));

        // PATCH partial update.
        mockMvc.perform(patch("/api/vendors/{id}", hotelId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"city\": \"Rishikesh\", \"dailyRate\": 3200.50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Rishikesh"))
                .andExpect(jsonPath("$.dailyRate").value(3200.50))
                .andExpect(jsonPath("$.name").value("Shivalik Inn"));

        // Deactivate: disappears from active-only list but not from all.
        mockMvc.perform(patch("/api/vendors/{id}", hotelId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/vendors?category=HOTEL").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + hotelId + "')]").doesNotExist());
        mockMvc.perform(get("/api/vendors?category=HOTEL&active=false").header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + hotelId + "')]").exists());
    }

    @Test
    void salesCannotCreateVendors() throws Exception {
        String sales = salesToken();

        mockMvc.perform(post("/api/vendors")
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "HOTEL", "name", "Unauthorized Inn"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalidGstinRejected() throws Exception {
        String token = managerToken();

        mockMvc.perform(post("/api/vendors")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "category", "HOTEL", "name", "Bad GST Hotel", "gstin", "SHORT"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void opsArrangementsEnforceVendorCategories() throws Exception {
        String token = managerToken();
        String tripId = createTrip(token, "Kedarkantha");
        String batchId = createBatch(token, tripId, "2026-10-02", 12);
        String bookingId = createBooking(token, tripId, batchId);
        confirm(token, bookingId);

        String guideId = createVendor(token, "GUIDE", "Deepak Sharma", "9876500201", null);
        String driverId = createVendor(token, "DRIVER", "Mahesh Yadav", "9876500202", null);
        String hotelId = createVendor(token, "HOTEL", "Sankri Lodge", "9876500203", null);
        String transportId = createVendor(token, "TRANSPORT", "Himalayan Cabs", "9876500204", null);

        String handoffId = handoffIdFor(bookingId);

        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "guideId", guideId,
                                "driverId", driverId,
                                "hotelVendorId", hotelId,
                                "transportVendorId", transportId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.guideName").value("Deepak Sharma"))
                .andExpect(jsonPath("$.driverName").value("Mahesh Yadav"))
                .andExpect(jsonPath("$.hotelVendorName").value("Sankri Lodge"))
                .andExpect(jsonPath("$.transportVendorName").value("Himalayan Cabs"));

        // A HOTEL vendor cannot fill the driver slot.
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"driverId\": \"" + hotelId + "\"}"))
                .andExpect(status().isBadRequest());

        // Unknown vendor ids are rejected everywhere.
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelVendorId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String salesToken() throws Exception {
        createUser("sales@securetravels.in", "Sales", Role.SALES, "sales123");
        return login("sales@securetravels.in", "sales123");
    }

    private String createVendor(String token, String category, String name, String phone, String gstin)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("category", category);
        body.put("name", name);
        body.put("phone", phone);
        if (gstin != null) {
            body.put("gstin", gstin);
        }
        String created = mockMvc.perform(post("/api/vendors")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createTrip(String token, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", "PILGRIMAGE");
        body.put("bookingType", "FIXED_BATCH");
        body.put("baseCost", 12000);
        body.put("durationDays", 4);
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBatch(String token, String tripId, String date, int cap) throws Exception {
        String created = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureDate", date, "maxCapacity", cap))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String customerId() {
        String mobile = "+9198" + (970000000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000L));
        return jdbcTemplate.queryForObject(
                "INSERT INTO customer360 (id, full_name, mobile_number, mobile_digits, consent_given) "
                        + "VALUES (gen_random_uuid(), 'Vendor Customer', '" + mobile + "', "
                        + "'" + mobile.substring(3) + "', true) RETURNING id",
                UUID.class).toString();
    }

    private String createBooking(String token, String tripId, String batchId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId());
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", 1);
        body.put("travelDate", "2026-10-02");
        body.put("travellers", List.of(Map.of("fullName", "Arun Traveler", "age", 29,
                "gender", "M", "medicalCertRequired", false, "phone", "9800700000")));
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private void confirm(String token, String bookingId) throws Exception {
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());
    }

    private String handoffIdFor(String bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM operations_handoffs WHERE booking_id = ?::uuid", UUID.class, bookingId).toString();
    }
}