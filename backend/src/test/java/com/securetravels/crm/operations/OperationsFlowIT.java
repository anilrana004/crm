package com.securetravels.crm.operations;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.operations.OperationsHandoff.HandoffStatus;
import com.securetravels.crm.payment.Payment;
import com.securetravels.crm.payment.PaymentSweep;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OperationsFlowIT extends BaseIT {

    @Autowired private OperationsHandoffRepository handoffRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private Customer360Repository customerRepository;
    @Autowired private PaymentSweep paymentSweep;

    @Test
    void confirmingBookingCreatesHandoffAndNotifiesOps() throws Exception {
        String manager = managerToken();
        UUID opsUser = opsUserId();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kedarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-08-01", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 3, "9862000001");

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/operations")
                        .header("Authorization", authHeader(opsToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bookingRef").value("TOH-2026-0001"))
                .andExpect(jsonPath("$[0].opsRef", org.hamcrest.Matchers.matchesPattern("OPS-2026-\\d{4}")))
                .andExpect(jsonPath("$[0].pax").value(3))
                .andExpect(jsonPath("$[0].travelDate").value("2026-08-01"))
                .andExpect(jsonPath("$[0].hotelStatus").value("NOT_ARRANGED"))
                .andExpect(jsonPath("$[0].transportStatus").value("NOT_ARRANGED"))
                .andExpect(jsonPath("$[0].paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$[0].customerName").value("Amit Verma"))
                .andExpect(jsonPath("$[0].tripName").isNotEmpty());

        // OPS prep task assigned to the ops user; they were notified in-app
        List<Task> prep = taskRepository.findByBookingIdAndTypeAndStatusIn(UUID.fromString(bookingId),
                Task.Type.OPS, List.of(Task.Status.PENDING, Task.Status.OVERDUE));
        assertThat(prep).hasSize(1);
        assertThat(prep.get(0).getAssigneeId()).isEqualTo(opsUser);

        List<Notification> opsNotifications =
                notificationRepository.findTop50ByUserIdOrderByCreatedAtDesc(opsUser);
        assertThat(opsNotifications).anyMatch(n ->
                n.getTitle().contains("New ops handoff") && n.getBody().contains("TOH-2026-0001"));
    }

    @Test
    void handoffCreatedOnceAndRefsAreDistinct() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kedarkantha");
        String batchId = createBatch(manager, tripId, "2026-08-05", 20);
        String b1 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000002");
        String b2 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000003");

        confirm(ravi, b1);
        confirm(ravi, b2);

        assertThat(handoffRepository.findByBookingId(UUID.fromString(b1))).isPresent();
        assertThat(handoffRepository.findByBookingId(UUID.fromString(b2))).isPresent();
        assertThat(handoffRepository.findByBookingId(UUID.fromString(b1)).orElseThrow().getOpsRef())
                .isNotEqualTo(handoffRepository.findByBookingId(UUID.fromString(b2)).orElseThrow().getOpsRef());
        assertThat(handoffRepository.findAllByOrderByCreatedAtDesc()).hasSize(2);

        // a booking can never be confirmed twice, so the "once" invariant cannot double-fire
        mockMvc.perform(patch("/api/bookings/{id}/status", b1)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingBookingKeepsHandoffWithNote() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Pangarchulla");
        String batchId = createBatch(manager, tripId, "2026-08-10", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000004");

        confirm(ravi, bookingId);
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        OperationsHandoff h = handoffRepository.findByBookingId(UUID.fromString(bookingId)).orElseThrow();
        assertThat(h.getNotes()).contains("TOH-2026-0001 cancelled");
    }

    @Test
    void arrangementsUpdatedByOpsOrManagerAndSalesBlocked() throws Exception {
        String manager = managerToken();
        String ops = opsToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Roopkund");
        String batchId = createBatch(manager, tripId, "2026-08-15", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000005");
        confirm(ravi, bookingId);

        String guideId = createGuide(manager, "Mohan Rawat");
        String handoffId = handoffIdFor(bookingId);

        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "hotelStatus", "CONFIRMED",
                                "transportStatus", "PENDING",
                                "guideId", guideId,
                                "driverId", UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotelStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.transportStatus").value("PENDING"))
                .andExpect(jsonPath("$.guideName").value("Mohan Rawat"))
                .andExpect(jsonPath("$.driverId").isNotEmpty());

        // sales cannot modify arrangements (ops surface)
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelStatus\": \"PENDING\"}"))
                .andExpect(status().isForbidden());

        // unknown guide rejected
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guideId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());

        // partial updates leave other fields intact
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffId)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transportStatus\": \"CONFIRMED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transportStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.hotelStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.guideName").value("Mohan Rawat"));
    }

    @Test
    void notesAppendPreservesHistory() throws Exception {
        String manager = managerToken();
        String ops = opsToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Hampta Pass");
        String batchId = createBatch(manager, tripId, "2026-08-20", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000006");
        confirm(ravi, bookingId);
        String handoffId = handoffIdFor(bookingId);

        mockMvc.perform(post("/api/operations/{id}/notes", handoffId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"hotel vendor confirmed Shivalik Inn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", containsString("[Suresh Rawat")))
                .andExpect(jsonPath("$.notes", containsString("Shivalik Inn")));

        mockMvc.perform(post("/api/operations/{id}/notes", handoffId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"driver finalised\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes", containsString("hotel vendor confirmed")))
                .andExpect(jsonPath("$.notes", containsString("driver finalised")));

        // blank note rejected
        mockMvc.perform(post("/api/operations/{id}/notes", handoffId)
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\": \"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tripSheetGeneratesAndRegenerates() throws Exception {
        String manager = managerToken();
        String ops = opsToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Ladakh Loop");
        String batchId = createBatch(manager, tripId, "2026-08-25", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000007");
        confirm(ravi, bookingId);
        String handoffId = handoffIdFor(bookingId);

        mockMvc.perform(post("/api/operations/{id}/trip-sheet", handoffId)
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripSheetGeneratedAt").isNotEmpty());

        MvcResult re = mockMvc.perform(post("/api/operations/{id}/trip-sheet", handoffId)
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(objectMapper.readTree(re.getResponse().getContentAsString())
                .get("tripSheetGeneratedAt").asText()).isNotEmpty();

        mockMvc.perform(get("/api/operations/{id}", handoffId)
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tripSheetGeneratedAt").isNotEmpty());
    }

    @Test
    void paymentStatusMirrorsReceipts() throws Exception {
        String manager = managerToken();
        String ops = opsToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Valley of Flowers");
        String batchId = createBatch(manager, tripId, "2026-09-01", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 3, "9862000008"); // net 75000
        confirm(ravi, bookingId);
        String handoffId = handoffIdFor(bookingId);

        assertThat(paymentStatusOf(ops, handoffId)).isEqualTo("PENDING");

        String advance = recordPayment(ravi, bookingId, 25000, "ADVANCE", today(5));
        complete(ravi, advance);
        assertThat(paymentStatusOf(ops, handoffId)).isEqualTo("PARTIAL");

        String balance = recordPayment(ravi, bookingId, 50000, "BALANCE", today(10));
        complete(ravi, balance);
        assertThat(paymentStatusOf(ops, handoffId)).isEqualTo("COMPLETED");

        // refund unwinds applied money -> back to partial
        mockMvc.perform(patch("/api/payments/{id}/status", balance)
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"REFUNDED\", \"note\": \"cancel hotel block refund\"}"))
                .andExpect(status().isOk());
        assertThat(paymentStatusOf(ops, handoffId)).isEqualTo("PARTIAL");
    }

    @Test
    void overdueSweepFlipsHandoffPaymentStatus() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Spiti Summer");
        String batchId = createBatch(manager, tripId, "2026-09-05", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000009");
        confirm(ravi, bookingId);

        recordPayment(ravi, bookingId, 25000, "BALANCE", today(-1));
        paymentSweep.runSweep();

        String ops = opsToken();
        assertThat(paymentStatusOf(ops, handoffIdFor(bookingId))).isEqualTo("OVERDUE");
    }

    @Test
    void rbacScopingAndFilters() throws Exception {
        String manager = managerToken();
        String ops = opsToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String meera = salesToken("meera", "meera@securetravels.in");
        String tripId = createTrip(manager, "Tarsar Marsar");
        String batchId = createBatch(manager, tripId, "2026-09-10", 20);
        String b1 = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000010");
        String b2 = createBookingFromCustomer(meera, tripId, batchId, 2, "9862000011");
        confirm(ravi, b1);
        confirm(meera, b2);

        // sales sees only their own booking's handoff
        mockMvc.perform(get("/api/operations").header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        // cross-owner detail forbidden
        mockMvc.perform(get("/api/operations/{id}", handoffIdFor(b2))
                        .header("Authorization", authHeader(ravi)))
                .andExpect(status().isForbidden());

        // ops and managers see everything; travel-date filter narrows
        mockMvc.perform(get("/api/operations").header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(get("/api/operations?travelDateFrom=2026-09-11")
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(get("/api/operations?hotelStatus=NOT_ARRANGED&paymentStatus=PENDING")
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // sales change arrangements -> blocked at the role check
        mockMvc.perform(patch("/api/operations/{id}/arrangements", handoffIdFor(b1))
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelStatus\": \"CONFIRMED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void neverConfirmedBookingHasNoHandoff() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kashmir Splendor");
        String batchId = createBatch(manager, tripId, "2026-09-15", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000012");

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        assertThat(handoffRepository.existsByBookingId(UUID.fromString(bookingId))).isFalse();
        mockMvc.perform(get("/api/operations").header("Authorization", authHeader(opsToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String opsToken() throws Exception {
        if (!userRepository.existsByEmailIgnoreCase("ops.suresh@securetravels.in")) {
            createUser("ops.suresh@securetravels.in", "Suresh Rawat", Role.OPS, "ops123");
        }
        return login("ops.suresh@securetravels.in", "ops123");
    }

    private UUID opsUserId() {
        if (!userRepository.existsByEmailIgnoreCase("ops.suresh@securetravels.in")) {
            createUser("ops.suresh@securetravels.in", "Suresh Rawat", Role.OPS, "ops123");
        }
        return userRepository.findByEmailIgnoreCase("ops.suresh@securetravels.in").orElseThrow().getId();
    }

    private String salesToken(String name, String email) throws Exception {
        createUser(email, name, Role.SALES, "sales123");
        return login(email, "sales123");
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

    private String createGuide(String token, String name) throws Exception {
        String created = mockMvc.perform(post("/api/guides")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "fullName", name, "phone", "9876500100", "dailyRate", 2500))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private Customer360 newCustomer(String phone) {
        return customerRepository.save(Customer360.fromLead("Amit Verma", phone, phone, phone,
                "amit" + phone + "@example.com", true, "contact for travel enquiry and follow-up"));
    }

    private String createBookingFromCustomer(String token, String tripId, String batchId, int travellers,
                                             String phone) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", newCustomer(phone).getId().toString());
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("amount", amount);
        body.put("amountType", amountType);
        body.put("dueDate", dueDate);
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
                        .content("{\"status\": \"COMPLETED\", \"paidAt\": \"" + nowIso()
                                + "\", \"gatewayRef\": \"TXN" + UUID.randomUUID().toString().substring(0, 12)
                                + "\", \"note\": \"receipt\"}"))
                .andExpect(status().isOk());
    }

    private String handoffIdFor(String bookingId) {
        return handoffRepository.findByBookingId(UUID.fromString(bookingId)).orElseThrow().getId().toString();
    }

    private String paymentStatusOf(String token, String handoffId) throws Exception {
        MvcResult res = mockMvc.perform(get("/api/operations/{id}", handoffId)
                        .header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("paymentStatus").asText();
    }

    private String today(int offset) {
        return LocalDate.now().plusDays(offset).toString();
    }

    private static String nowIso() {
        return java.time.Instant.now().toString();
    }
}