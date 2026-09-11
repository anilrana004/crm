package com.securetravels.crm.payment;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
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

class PaymentFlowIT extends BaseIT {

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private Customer360Repository customerRepository;
    @Autowired private PaymentSweep paymentSweep;

    @Test
    void advanceThenBalanceReachesZeroAndZeroNegativeAmountsRejected() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kedarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-08-01", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 3, "9862000001"); // net 75000

        // advance recorded but not yet collected
        String advance = recordPayment(ravi, bookingId, 25000, "ADVANCE", today(5));
        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.netAmount").value(75000))
                .andExpect(jsonPath("$.appliedAmount").value(0))
                .andExpect(jsonPath("$.balanceAmount").value(75000))
                .andExpect(jsonPath("$.nextDueDate").value(today(5)));

        complete(ravi, advance);
        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(jsonPath("$.appliedAmount").value(25000))
                .andExpect(jsonPath("$.balanceAmount").value(50000));

        String balance = recordPayment(ravi, bookingId, 50000, "BALANCE", today(10));
        complete(ravi, balance);
        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(jsonPath("$.appliedAmount").value(75000))
                .andExpect(jsonPath("$.balanceAmount").value(0));

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(bookingId, 0, "ADVANCE", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].message", containsString("greater than zero")));

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(bookingId, "-100", "ADVANCE", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void completingWithoutPaidAtIsRejected() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kasmir Splendor");
        String batchId = createBatch(manager, tripId, "2026-08-10", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000002");

        String advance = recordPayment(ravi, bookingId, 5000, "ADVANCE", today(3));
        mockMvc.perform(patch("/api/payments/{id}/status", advance)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("paidAt")));
    }

    @Test
    void refundReducesAppliedAmount() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Hampta Pass");
        String batchId = createBatch(manager, tripId, "2026-08-15", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000003"); // 50000

        String advance = recordPayment(ravi, bookingId, 30000, "ADVANCE", today(2));
        complete(ravi, advance);

        mockMvc.perform(patch("/api/payments/{id}/status", advance)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"REFUNDED\", \"paidAt\": \"" + nowIso() + "\", \"note\": \"refunded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(jsonPath("$.appliedAmount").value(0))
                .andExpect(jsonPath("$.balanceAmount").value(50000));
    }

    @Test
    void illegalTransitionsConflict() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Ladakh Loop");
        String batchId = createBatch(manager, tripId, "2026-08-20", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000004");

        String line = recordPayment(ravi, bookingId, 10000, "BALANCE", today(1));
        // PENDING -> REFUNDED is illegal
        mockMvc.perform(patch("/api/payments/{id}/status", line)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"REFUNDED\"}"))
                .andExpect(status().isConflict());

        complete(ravi, line);
        // COMPLETED -> CANCELLED is illegal
        mockMvc.perform(patch("/api/payments/{id}/status", line)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isConflict());
        // same-state no-op
        mockMvc.perform(patch("/api/payments/{id}/status", line)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void reminderSweepCreatesOnceAndCompletingPaymentRetiresIt() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Spiti Summer");
        String batchId = createBatch(manager, tripId, "2026-08-25", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000005");

        // outside the 3-day horizon first
        String farLine = recordPayment(ravi, bookingId, 5000, "BALANCE", today(6));
        paymentSweep.runSweep();
        assertThat(openReminders(bookingId)).isEmpty();

        // now due within 3 days
        String nearLine = recordPayment(ravi, bookingId, 20000, "BALANCE", today(2));
        paymentSweep.runSweep();
        assertThat(openReminders(bookingId)).hasSize(1);

        paymentSweep.runSweep();
        assertThat(openReminders(bookingId)).hasSize(1); // deduped

        Task reminder = openReminders(bookingId).get(0);
        assertThat(reminder.getAssigneeId().toString())
                .isEqualTo(userRepository.findByEmailIgnoreCase("ravi@securetravels.in").orElseThrow().getId().toString());

        complete(ravi, nearLine);
        assertThat(openReminders(bookingId)).isEmpty();
    }

    @Test
    void overdueSweepFlagsPastDueLinesAndReminds() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Roopkund Trek");
        String batchId = createBatch(manager, tripId, "2026-09-01", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000006");

        String line = recordPayment(ravi, bookingId, 25000, "BALANCE", today(-1));
        paymentSweep.runSweep();

        mockMvc.perform(get("/api/payments?bookingId={id}", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("OVERDUE"));

        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(jsonPath("$.hasOverdueLine").value(true));

        assertThat(openReminders(bookingId)).hasSize(1);
    }

    @Test
    void rbacOwnersOnlyAndManagersOverride() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String meera = salesToken("meera", "meera@securetravels.in");
        String ops = opsToken();

        String tripId = createTrip(manager, "Tarsar Marsar");
        String batchId = createBatch(manager, tripId, "2026-09-10", 20);

        String booking1 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000007");
        String payment1 = recordPayment(ravi, booking1, 10000, "ADVANCE", today(4));

        String booking2 = createBookingFromCustomer(meera, tripId, batchId, 1, "9862000008");
        recordPayment(meera, booking2, 12000, "ADVANCE", today(4));

        // cross-owner write forbidden
        mockMvc.perform(patch("/api/payments/{id}/status", payment1)
                        .header("Authorization", authHeader(meera))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"PARTIAL\"}"))
                .andExpect(status().isForbidden());

        // ops cannot record
        mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(booking1, 5000, "ADVANCE", today(2))))
                .andExpect(status().isForbidden());

        // list scoping: owner sees own, manager sees all
        mockMvc.perform(get("/api/payments").header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].bookingRef").isNotEmpty());

        mockMvc.perform(get("/api/payments").header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        // ops can read any statement, not write
        mockMvc.perform(get("/api/payments/booking/{id}/summary", booking1)
                        .header("Authorization", authHeader(ops)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAmount").value(25000));
    }

    @Test
    void cancelledBookingRejectsNewLinesAndAutoCancelsPending() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Kedarkantha Solid");
        String batchId = createBatch(manager, tripId, "2026-09-15", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000009");

        String pendingLine = recordPayment(ravi, bookingId, 20000, "ADVANCE", today(3));
        String paidLine = recordPayment(ravi, bookingId, 15000, "BALANCE", today(1));
        complete(ravi, paidLine);

        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CANCELLED\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(bookingId, 1000, "BALANCE", today(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("cancelled")));

        assertThat(paymentRepository.findById(UUID.fromString(pendingLine)).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.CANCELLED);
        assertThat(paymentRepository.findById(UUID.fromString(paidLine)).orElseThrow().getStatus())
                .isEqualTo(Payment.Status.COMPLETED);
    }

    @Test
    void fullPaymentRecordingSettlesBalance() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String tripId = createTrip(manager, "Valley of Flowers");
        String batchId = createBatch(manager, tripId, "2026-09-20", 20);
        String bookingId = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000010");

        String full = recordPayment(ravi, bookingId, 25000, "FULL", today(7));
        complete(ravi, full);

        mockMvc.perform(get("/api/payments/booking/{id}/summary", bookingId)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(jsonPath("$.appliedAmount").value(25000))
                .andExpect(jsonPath("$.balanceAmount").value(0));
    }

    // ------------------------------------------------------------------ helpers

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

private String opsToken() throws Exception {
        createUser("ops.suresh@securetravels.in", "Suresh", Role.OPS, "ops123");
        return login("ops.suresh@securetravels.in", "ops123");
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
        body.put("travellers", travellerList(travellers));
        String created = mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private String recordPayment(String token, String bookingId, int amount, String amountType, String dueDate)
            throws Exception {
        MvcResult res = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(bookingId, amount, amountType, dueDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amountType").value(amountType))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String paymentBody(String bookingId, Object amount, String amountType, String dueDate) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bookingId", bookingId);
        body.put("amount", amount);
        body.put("amountType", amountType);
        body.put("dueDate", dueDate);
        return objectMapper.writeValueAsString(body);
    }

    private void complete(String token, String paymentId) throws Exception {
        mockMvc.perform(patch("/api/payments/{id}/status", paymentId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"COMPLETED\", \"paidAt\": \"" + nowIso()
                                + "\", \"gatewayRef\": \"TXN" + uuid() + "\", \"note\": \"receipt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty());
    }

    private List<Task> openReminders(String bookingId) {
        return taskRepository.findByBookingIdAndTypeAndStatusIn(UUID.fromString(bookingId),
                Task.Type.PAYMENT_REMINDER, List.of(Task.Status.PENDING, Task.Status.OVERDUE));
    }

    private String today(int offset) {
        return LocalDate.now().plusDays(offset).toString();
    }

    private static String nowIso() {
        return java.time.Instant.now().toString();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().substring(0, 12);
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