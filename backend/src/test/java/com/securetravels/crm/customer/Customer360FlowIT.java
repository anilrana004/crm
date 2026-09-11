package com.securetravels.crm.customer;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.dto.CustomerUpdateRequest;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

class Customer360FlowIT extends BaseIT {

    @Autowired private Customer360Repository customerRepository;

    @Test
    void listIsEmptyWhenNoCustomers() throws Exception {
        String manager = managerToken();
        mockMvc.perform(get("/api/customers").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void bookingConfirmsCustomerAndTripsShowInHistory() throws Exception {
        String manager = managerToken();
        String ravi = salesToken();
        String tripId = createTrip(manager, "Kedarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-08-01", 20);
        String customerId = seedCustomer();
        String bookingId = createBooking(ravi, customerId, tripId, batchId, 2);

        confirm(ravi, bookingId);

        // customer appears in list with 1 trip
        mockMvc.perform(get("/api/customers").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fullName").value("Amit Verma"))
                .andExpect(jsonPath("$[0].mobileNumber").value("9862000001"))
                .andExpect(jsonPath("$[0].totalTrips").value(1))
                .andExpect(jsonPath("$[0].lastTripDate").value("2026-08-01"))
                .andExpect(jsonPath("$[0].totalSpent").value(0));

        // detail shows the trip history entry
        mockMvc.perform(get("/api/customers/{id}", customerId).header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Amit Verma"))
                .andExpect(jsonPath("$.tripHistory").isArray())
                .andExpect(jsonPath("$.tripHistory[0].tripName").value("Kedarnath Yatra"))
                .andExpect(jsonPath("$.tripHistory[0].travelDate").value("2026-08-01"))
                .andExpect(jsonPath("$.tripHistory[0].status").value("CONFIRMED"))
                .andExpect(jsonPath("$.tripHistory[0].netAmount").value(50000));
    }

    @Test
    void searchFiltersCustomersByNameAndMobile() throws Exception {
        String manager = managerToken();
        seedCustomer();
        seedCustomer2();

        mockMvc.perform(get("/api/customers?search=Verma").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].fullName").value("Amit Verma"));

        mockMvc.perform(get("/api/customers?search=9862000002").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].fullName").value("Sonia Mehta"));
    }

    @Test
    void cancelledBookingNotCountedInTripHistory() throws Exception {
        String manager = managerToken();
        String ravi = salesToken();
        String tripId = createTrip(manager, "Char Dham");
        String batchId = createBatch(manager, tripId, "2026-08-05", 20);
        String customerId = seedCustomer();
        String bookingId = createBooking(ravi, customerId, tripId, batchId, 1);

        confirm(ravi, bookingId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/customers/{id}", customerId).header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTrips").value(0))
                .andExpect(jsonPath("$.lastTripDate").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.tripHistory").isEmpty());
    }

    @Test
    void spendReflectsAppliedReceiptsNotJustNet() throws Exception {
        String manager = managerToken();
        String ravi = salesToken();
        String tripId = createTrip(manager, "Roopkund");
        String batchId = createBatch(manager, tripId, "2026-08-10", 20);
        String customerId = seedCustomer();
        String bookingId = createBooking(ravi, customerId, tripId, batchId, 1); // net 25000
        confirm(ravi, bookingId);

        String paymentId = recordPayment(ravi, bookingId, 10000, "ADVANCE", "2026-08-01");
        complete(ravi, paymentId);

        mockMvc.perform(get("/api/customers/{id}", customerId).header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTrips").value(1))
                .andExpect(jsonPath("$.lastTripDate").value("2026-08-10"))
                .andExpect(jsonPath("$.totalSpent").value(10000))
                .andExpect(jsonPath("$.tripHistory[0].appliedAmount").value(10000));

        // refunding unwinds the spend figure
        mockMvc.perform(patch("/api/payments/{id}/status", paymentId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"REFUNDED\", \"note\": \"test refund\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/customers/{id}", customerId).header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSpent").value(0))
                .andExpect(jsonPath("$.tripHistory[0].appliedAmount").value(0));
    }

    @Test
    void managerUpdatesFlagsAndSalesCannot() throws Exception {
        String manager = managerToken();
        String ravi = salesToken();
        String customerId = seedCustomer();

        mockMvc.perform(patch("/api/customers/{id}", customerId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CustomerUpdateRequest(
                                "Kashmir family tour package",
                                new String[]{"Kashmir", "Family tour"},
                                true,
                                "Prefers December departure"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestOffer").value("Kashmir family tour package"))
                .andExpect(jsonPath("$.offerTags[0]").value("Kashmir"))
                .andExpect(jsonPath("$.marketingOptIn").value(true))
                .andExpect(jsonPath("$.notes").value("Prefers December departure"));

        // sales cannot maintain flags
        mockMvc.perform(patch("/api/customers/{id}", customerId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"suggestOffer\": \"blocked\"}"))
                .andExpect(status().isForbidden());

        // unsupported tag rejected
        mockMvc.perform(patch("/api/customers/{id}", customerId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"offerTags\": [\"Zorbing\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authenticatedUserCanReadCustomerDetail() throws Exception {
        String manager = managerToken();
        String ravi = salesToken();
        String customerId = seedCustomer();

        mockMvc.perform(get("/api/customers/{id}", customerId).header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Amit Verma"));

        // missing id -> 404
        mockMvc.perform(get("/api/customers/{id}", UUID.randomUUID()).header("Authorization", authHeader(ravi)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String salesToken() throws Exception {
        createUser("ravi@securetravels.in", "Ravi Singh", Role.SALES, "sales123");
        return login("ravi@securetravels.in", "sales123");
    }

    private String seedCustomer() {
        Customer360 c = Customer360.fromLead("Amit Verma", "9862000001", "9862000001", "9862000001",
                "amit@example.com", true, "travel enquiry");
        return customerRepository.save(c).getId().toString();
    }

    private void seedCustomer2() {
        Customer360 c = Customer360.fromLead("Sonia Mehta", "9862000002", "9862000002", "9862000002",
                "sonia@example.com", true, "travel enquiry");
        customerRepository.save(c);
    }

    private String createTrip(String token, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", "TREK");
        body.put("bookingType", "FIXED_BATCH");
        body.put("baseCost", 25000);
        body.put("durationDays", 5);
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBatch(String token, String tripId, String departureDate, int capacity) throws Exception {
        String created = mockMvc.perform(post("/api/trips/{tripId}/batches", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "departureDate", departureDate, "maxCapacity", capacity))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBooking(String token, String customerId, String tripId, String batchId, int travellers)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", travellers);
        List<Map<String, Object>> travellersList = new java.util.ArrayList<>();
        for (int i = 1; i <= travellers; i++) {
            travellersList.add(Map.of("fullName", "Traveller " + i, "age", 30,
                    "gender", i % 2 == 0 ? "F" : "M", "medicalCertRequired", false));
        }
        body.put("travellers", travellersList);
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

    private String recordPayment(String token, String bookingId, int amount, String amountType, String dueDate)
            throws Exception {
        String created = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "bookingId", bookingId, "amount", amount, "amountType", amountType,
                                "dueDate", dueDate))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private void complete(String token, String paymentId) throws Exception {
        mockMvc.perform(patch("/api/payments/{id}/status", paymentId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\", \"paidAt\": \"" + java.time.Instant.now()
                                + "\", \"gatewayRef\": \"TXN" + UUID.randomUUID().toString().substring(0, 12)
                                + "\", \"note\": \"receipt\"}"))
                .andExpect(status().isOk());
    }
}