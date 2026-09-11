package com.securetravels.crm.phase1;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.booking.BookingService;
import com.securetravels.crm.booking.dto.BookingCreateRequest;
import com.securetravels.crm.booking.dto.BookingResponse;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.operations.OperationsHandoff;
import com.securetravels.crm.operations.OperationsHandoffRepository;
import com.securetravels.crm.trip.Batch;
import com.securetravels.crm.trip.BatchRepository;
import com.securetravels.crm.trip.SeatHold;
import com.securetravels.crm.trip.SeatHoldRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 1 hardening — mandatory seam/concurrency test and end-to-end flows:
 *   • exactly one caller wins a single remaining seat under real parallelism;
 *   • discount approval threshold: at-or-below-limit auto-approved by sales,
 *     over-limit blocked for sales and unblocked by a manager;
 *   • the lead → booking → confirm → ops handoff → payment chain.
 */
class Phase1HardeningIT extends BaseIT {

    @Autowired private BookingService bookingService;
    @Autowired private BatchRepository batchRepository;
    @Autowired private SeatHoldRepository seatHoldRepository;
    @Autowired private OperationsHandoffRepository handoffRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private Customer360Repository customerRepository;

    @Test
    void oneSeatBatchAdmitsExactlyOneOfTwoConcurrentClaims() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Concurrency Trek");
        String batchId = createBatch(manager, tripId, "2026-10-05", 1);

        UUID salesId = createUser("sales.conc@securetravels.in", "Conc", Role.SALES, "sales123");
        UserPrincipal caller = new UserPrincipal(salesId, "sales.conc@securetravels.in", "Conc", Role.SALES, true);
        UUID customerA = UUID.fromString(newCustomer("9810000001"));
        UUID customerB = UUID.fromString(newCustomer("9810000002"));
        UUID trip = UUID.fromString(tripId);
        UUID batch = UUID.fromString(batchId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<BookingResponse> claimA = pool.submit(() -> {
            start.await();
            return bookingService.create(new BookingCreateRequest(customerA, null, trip, batch,
                    null, 1, null, null, null), caller);
        });
        Future<BookingResponse> claimB = pool.submit(() -> {
            start.await();
            return bookingService.create(new BookingCreateRequest(customerB, null, trip, batch,
                    null, 1, null, null, null), caller);
        });
        start.countDown();

        int created = 0;
        int rejected = 0;
        for (Future<BookingResponse> claim : List.of(claimA, claimB)) {
            try {
                claim.get(30, TimeUnit.SECONDS);
                created++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(BadRequestException.class);
                rejected++;
            }
        }
        pool.shutdownNow();

        assertThat(created).isEqualTo(1);
        assertThat(rejected).isEqualTo(1);

        Batch after = batchRepository.findById(batch).orElseThrow();
        assertThat(after.getSeatsBooked()).isEqualTo(1);
        assertThat(after.seatsAvailable()).isZero();
        assertThat(after.getStatus()).isEqualTo(Batch.Status.CLOSED);
        assertThat(seatHoldRepository.findAll())
                .singleElement()
                .satisfies(hold -> assertThat(hold.getStatus()).isEqualTo(SeatHold.Status.HELD));
    }

    @Test
    void discountWithinLimitIsAutoApprovedBySales() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Auto Discount", "CUSTOM_FIT");
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer("9810000003");

        // 2,400 on a 50,000 gross = 4.8% — at or below the 5% auto-approval limit.
        String bookingId = createDiscountedBooking(sales, tripId, customerId, 2400);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(sales))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.discountApprovedBy").value(raviUserId()));
    }

    @Test
    void discountOverLimitBlocksSalesUntilManagerApproves() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "Manager Discount", "CUSTOM_FIT");
        String sales = salesToken("ravi", "ravi@securetravels.in");
        String customerId = newCustomer("9810000004");

        // 2,600 on 50,000 gross = 5.2% — over the limit, so an approval is required.
        String bookingId = createDiscountedBooking(sales, tripId, customerId, 2600);

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

        UUID booking = UUID.fromString(bookingId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void leadToBookingToPaymentToOpsEndToEnd() throws Exception {
        String manager = managerToken();
        String tripId = createTrip(manager, "E2E Chain");
        String batchId = createBatch(manager, tripId, "2026-11-01", 20);
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

        OperationsHandoff handoff = handoffRepository.findByBookingId(UUID.fromString(bookingId)).orElseThrow();
        assertThat(handoff.getPaymentStatus().name()).isEqualTo("PENDING");

        String advance = recordPayment(sales, bookingId, 25000, "ADVANCE", LocalDate.now().plusDays(5));
        complete(sales, advance);

        OperationsHandoff paid = handoffRepository.findByBookingId(UUID.fromString(bookingId)).orElseThrow();
        assertThat(paid.getPaymentStatus().name()).isEqualTo("PARTIAL");
        assertThat(paid.getTravelDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(paid.getPax()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String managerUserId() {
        return userRepository.findByEmailIgnoreCase("manager@securetravels.in").orElseThrow().getId().toString();
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

    private String recordPayment(String token, String bookingId, int amount, String type, LocalDate dueDate)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("amount", amount);
        body.put("amountType", type);
        body.put("dueDate", dueDate.toString());
        String created = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private void complete(String token, String paymentId) throws Exception {
        mockMvc.perform(patch("/api/payments/{id}/status", paymentId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\", \"paidAt\": \"" + Instant.now() + "\"}"))
                .andExpect(status().isOk());
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