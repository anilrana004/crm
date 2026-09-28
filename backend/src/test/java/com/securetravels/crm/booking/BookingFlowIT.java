package com.securetravels.crm.booking;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.commission.CommissionLedger;
import com.securetravels.crm.commission.CommissionLedgerRepository;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.SeatHold;
import com.securetravels.crm.trip.SeatHoldRepository;
import com.securetravels.crm.trip.SeatHoldSweep;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookingFlowIT extends BaseIT {

    @Autowired private BatchRepository batchRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private Customer360Repository customerRepository;

    @Autowired private CommissionLedgerRepository commissionLedgerRepository;
    @Autowired private SeatHoldSweep seatHoldSweep;

    @Test
    void fixedBatchBookingHoldsSeatsConfirmsAndCountsSeatPool() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Kashmir Splendor");
        String batchId = createBatch(manager, tripId, "2026-06-20", 20);

        String sales = salesToken("ravi", "ravi@securetravels.in");
        String bookingId = createBookingFromCustomer(sales, tripId, batchId, 3, "9861000001");

        Batch batch = batchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        assertThat(batch.getSeatsBooked()).isEqualTo(3);
        assertThat(batch.seatsAvailable()).isEqualTo(17);
        assertThat(batch.getStatus()).isEqualTo(Batch.Status.OPEN);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.netAmount").value(75000));

        SeatHold hold = seatHoldRepository.findByBookingIdAndStatus(UUID.fromString(bookingId), SeatHold.Status.CONFIRMED)
                .stream().findFirst().orElseThrow();
        assertThat(hold.getNumSeats()).isEqualTo(3);
        assertThat(batchRepository.findById(UUID.fromString(batchId)).orElseThrow().getSeatsBooked()).isEqualTo(3);
    }

    @Test
    void capacityOverflowRejectedAndFullBatchAutoCloses() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Kedarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-07-01", 10);

        String sales = salesToken("ravi", "ravi@securetravels.in");
        createBookingFromCustomer(sales, tripId, batchId, 7, "9861000002");
        createBookingFromCustomer(sales, tripId, batchId, 3, "9861000003");

        assertThat(batchRepository.findById(UUID.fromString(batchId)).orElseThrow().getStatus())
                .isEqualTo(Batch.Status.CLOSED);

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bookingBodyFromCustomer("9811111111", tripId, batchId, 2))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("0 seats")));
    }

    @Test
    void customFitBookingUsesCustomerDateAndRejectsBatch() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Custom Honeymoon", "CUSTOM_FIT");
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer("9812222222");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("tripId", tripId);
        body.put("travelDate", "2026-12-24");
        body.put("numTravellers", 2);

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingType").value("CUSTOM_FIT"))
                .andExpect(jsonPath("$.travelDate").value("2026-12-24"))
                .andExpect(jsonPath("$.batchId").isEmpty());
    }

    @Test
    void discountedBookingNeedsManagerConfirmation() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Rim of Ladakh", "CUSTOM_FIT");
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer("9813333333");

        String bookingId = createDiscountedBooking(sales, tripId, customerId, 4000); // 8% of 50k gross > 5% limit

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.discountApprovedBy").value(managerUserId()));
    }

    @Test
    void cancelReleasesSeatsAndReopensAutoClosedBatch() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Valley of Flowers");
        String batchId = createBatch(manager, tripId, "2026-08-10", 4);

        String sales = salesToken("ravi", "ravi@securetravels.in");
        String bookingA = createBookingFromCustomer(sales, tripId, batchId, 2, "9861000004");
        createBookingFromCustomer(sales, tripId, batchId, 2, "9861000005");

        assertThat(batchRepository.findById(UUID.fromString(batchId)).orElseThrow().getStatus())
                .isEqualTo(Batch.Status.CLOSED);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingA)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Batch reopened = batchRepository.findById(UUID.fromString(batchId)).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(Batch.Status.OPEN);
        assertThat(reopened.getSeatsBooked()).isEqualTo(2);
        assertThat(seatHoldRepository.findByBookingIdAndStatus(UUID.fromString(bookingA), SeatHold.Status.RELEASED))
                .hasSize(1);
    }

    @Test
    void salesSeesOnlyOwnBookingsManagersSeeAll() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Char Dham", "CUSTOM_FIT");

        String salesA = salesToken("ravi", "ravi@securetravels.in");
        String salesB = salesToken("priya", "priya@securetravels.in");

        createDiscountedBooking(salesA, tripId, newCustomer("9814444444"), 0);
        createDiscountedBooking(salesB, tripId, newCustomer("9815555555"), 0);

        mockMvc.perform(get("/api/bookings").header("Authorization", authHeader(salesA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].createdBy").value(raviUserId()));

        mockMvc.perform(get("/api/bookings").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void expiredHoldIsReleasedBySweepAndBookingCanNoLongerConfirm() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Goa Beach Camp");
        String batchId = createBatch(manager, tripId, "2026-09-01", 10);
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String bookingId = createBookingFromCustomer(sales, tripId, batchId, 4, "9861000006");

        jdbcTemplate.update("UPDATE seat_holds SET held_until = now() - interval '10 minutes' WHERE booking_id = ?",
                UUID.fromString(bookingId));
        seatHoldSweep.releaseExpired();

        assertThat(batchRepository.findById(UUID.fromString(batchId)).orElseThrow().getSeatsBooked()).isZero();
        assertThat(seatHoldRepository.findByBookingIdAndStatus(UUID.fromString(bookingId), SeatHold.Status.EXPIRED))
                .hasSize(1);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void confirmingLeadBookingAdvancesLeadAndCancelsOpenFollowUps() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Amarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-07-15", 20);

        String sales = salesToken("ravi", "ravi@securetravels.in");
        String leadId = createLead(sales, tripId);
        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"QUOTATION_SENT\"}"))
                .andExpect(status().isOk());

        String bookingId = createBookingFromLead(sales, tripId, batchId, leadId);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());

        assertThat(leadRepository.findById(UUID.fromString(leadId)).orElseThrow().getStatus().name())
                .isEqualTo("BOOKING_CONFIRMED");
        List<Task> tasks = taskRepository.findByLeadIdOrderByDueAtAsc(UUID.fromString(leadId));
        assertThat(tasks).isNotEmpty();
        assertThat(tasks).allMatch(t -> t.getStatus() == Task.Status.CANCELLED);
    }

    @Test
    void cancellingConfirmedBookingReopensLeadToQuotationSent() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Netravati Trek");
        String batchId = createBatch(manager, tripId, "2026-08-20", 20);
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String leadId = createLead(sales, tripId);

        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"QUOTATION_SENT\"}"))
                .andExpect(status().isOk());

        String bookingId = createBookingFromLead(sales, tripId, batchId, leadId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        assertThat(leadRepository.findById(UUID.fromString(leadId)).orElseThrow().getStatus().name())
                .isEqualTo("QUOTATION_SENT");
    }

    /**
     * The revenue-attribution snapshot must be written by the booking flow itself,
     * not computed later by a report. If it were derived on read from
     * leads.owner_id, reassigning the lead would silently rewrite this booking's
     * history, which is the whole reason the ledger exists.
     */
    @Test
    void confirmingABookingCreditsTheLedgerToTheLeadOwner() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Ledger Trek");
        String batchId = createBatch(manager, tripId, "2026-09-20", 20);
        String sales = salesToken("meera", "meera@securetravels.in");
        String leadId = createLead(sales, tripId);

        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"QUOTATION_SENT\"}"))
                .andExpect(status().isOk());

        String bookingId = createBookingFromLead(sales, tripId, batchId, leadId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());

        CommissionLedger credit = commissionLedgerRepository
                .findByBookingId(UUID.fromString(bookingId))
                .orElseThrow(() -> new AssertionError("confirming a booking must credit the ledger"));

        assertThat(credit.getConsultantId())
                .as("the credit belongs to the lead's owner, not to whoever saved the booking")
                .isEqualTo(UUID.fromString(userIdByEmail("meera@securetravels.in")));
        assertThat(credit.getLeadId()).isEqualTo(UUID.fromString(leadId));
        assertThat(credit.isRevoked()).isFalse();
        assertThat(credit.getBookingStatusAtCredit())
                .as("the snapshot records CONFIRMED, not a live relation to the booking")
                .isEqualTo(Booking.Status.CONFIRMED);
        assertThat(credit.getNetAmount()).isNotNull();
    }

    @Test
    void cancellingRevokesTheCreditAndKeepsTheRow() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Revoke Trek");
        String batchId = createBatch(manager, tripId, "2026-10-20", 20);
        String sales = salesToken("suresh", "suresh@securetravels.in");
        String leadId = createLead(sales, tripId);

        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"QUOTATION_SENT\"}"))
                .andExpect(status().isOk());

        String bookingId = createBookingFromLead(sales, tripId, batchId, leadId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        CommissionLedger credit = commissionLedgerRepository
                .findByBookingId(UUID.fromString(bookingId))
                .orElseThrow(() -> new AssertionError("the credit must survive cancellation, not be deleted"));

        assertThat(credit.isRevoked()).isTrue();
        assertThat(credit.getRevokeReason()).contains("cancelled");
        assertThat(credit.effectiveNetAmount())
                .as("a revoked credit contributes nothing to revenue")
                .isEqualByComparingTo("0.00");
        assertThat(credit.getNetAmount())
                .as("but the original amount is still on the record for the dispute trail")
                .isPositive();
    }

    /** A second confirm attempt is rejected upstream, so the credit must not duplicate. */
    @Test
    void aBookingIsNeverCreditedTwice() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Once Trek");
        String batchId = createBatch(manager, tripId, "2026-11-20", 20);
        String sales = salesToken("anjali", "anjali@securetravels.in");
        String leadId = createLead(sales, tripId);

        mockMvc.perform(patch("/api/leads/{id}/status", leadId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"QUOTATION_SENT\"}"))
                .andExpect(status().isOk());

        String bookingId = createBookingFromLead(sales, tripId, batchId, leadId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());

        // Re-confirming a CONFIRMED booking is a conflict, not a second credit.
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().is4xxClientError());

        long credits = commissionLedgerRepository.findAll().stream()
                .filter(c -> c.getBookingId().equals(UUID.fromString(bookingId)))
                .count();
        assertThat(credits).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String managerUserId() {
        return userRepository.findByEmailIgnoreCase("manager@securetravels.in").orElseThrow().getId().toString();
    }

    private String userIdByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email).orElseThrow().getId().toString();
    }

    private String raviUserId() {
        return userRepository.findByEmailIgnoreCase("ravi@securetravels.in").orElseThrow().getId().toString();
    }

    private String salesToken(String name, String email) throws Exception {
        createUser(email, name, Role.SALES, "sales123");
        return login(email, "sales123");
    }

    private String createTrip(String token, String name) throws Exception {
        return createTrip(token, name, "FIXED_BATCH");
    }

    private String createTrip(String token, String name, String bookingType) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", "PILGRIMAGE");
        body.put("bookingType", bookingType);
        body.put("baseCost", 25000);
        body.put("durationDays", 6);
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

    private String newCustomer(String phone) {
        Customer360 customer = Customer360.fromLead("Amit Verma", phone, phone, phone,
                "amit" + phone + "@example.com", true, "contact for travel enquiry and follow-up");
        return customerRepository.save(customer).getId().toString();
    }

    private String createLead(String token, String tripId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerName", "Amit Verma");
        body.put("mobileNumber", "9876543210");
        body.put("source", "WEBSITE");
        body.put("destination", "Kashmir");
        body.put("tripId", tripId);
        body.put("consentGiven", true);
        String created = mockMvc.perform(post("/api/leads")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createBookingFromCustomer(String token, String tripId, String batchId, int travellers,
                                             String phone) throws Exception {
        String body = objectMapper.writeValueAsString(bookingBodyFromCustomer(phone, tripId, batchId, travellers));
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private Map<String, Object> bookingBodyFromCustomer(String phone, String tripId, String batchId, int travellers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", newCustomer(phone));
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", travellers);
        body.put("travellers", travellerList(travellers));
        return body;
    }

    private String createBookingFromLead(String token, String tripId, String batchId, String leadId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leadId", leadId);
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", 2);
        body.put("travellers", travellerList(2));
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String createDiscountedBooking(String token, String tripId, String customerId, int discount) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("tripId", tripId);
        body.put("travelDate", "2026-12-01");
        body.put("numTravellers", 2);
        body.put("discountAmount", discount);
        body.put("travellers", travellerList(2));
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private List<Map<String, Object>> travellerList(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> Map.<String, Object>of(
                        "fullName", "Traveller " + (i + 1),
                        "age", 30 + i,
                        "gender", i % 2 == 0 ? "M" : "F",
                        "medicalCertRequired", false))
                .toList();
    }
}