package com.securetravels.crm.dashboard;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Module 8: M4 dashboard cards, M5 performance, M6 targets + RBAC. */
class DashboardAndTargetsIT extends BaseIT {

    @Autowired private LeadRepository leadRepository;
    @Autowired private TaskRepository taskRepository;
    @Autowired private Customer360Repository customerRepository;

    @Test
    void summaryCardsReadPipelineBookingsAndCollectedRevenue() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");

        UUID raviId = userRepository.findByEmailIgnoreCase("ravi@securetravels.in").orElseThrow().getId();
        seedLead(raviId, Lead.Status.NEW, null);
        seedLead(raviId, Lead.Status.INTERESTED, LocalDate.now().minusDays(1));
        seedLead(raviId, Lead.Status.QUOTATION_SENT, null);
        seedLead(raviId, Lead.Status.BOOKING_CONFIRMED, null);
        seedLead(raviId, Lead.Status.LOST, null);

        String tripId = createTrip(manager, "Kedarnath Yatra");
        String batchId = createBatch(manager, tripId, "2026-08-01", 20);
        String b1 = createBookingFromCustomer(ravi, tripId, batchId, 2, "9862000001");
        String b2 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000002");
        confirm(manager, b1);
        confirm(manager, b2);

        String p1 = recordPayment(ravi, b1, 10000, "ADVANCE", today(5));
        complete(ravi, p1);
        String p2 = recordPayment(ravi, b2, 5000, "ADVANCE", today(6));
        complete(ravi, p2);

        mockMvc.perform(get("/api/dashboard/summary").param("period", "month")
                        .header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value("month"))
                .andExpect(jsonPath("$.totalLeads").value(5))
                .andExpect(jsonPath("$.newLeads").value(1))
                .andExpect(jsonPath("$.followUpDue").value(1))
                .andExpect(jsonPath("$.interested").value(1))
                .andExpect(jsonPath("$.quotationSent").value(1))
                .andExpect(jsonPath("$.bookingConfirmed").value(1))
                .andExpect(jsonPath("$.lost").value(1))
                .andExpect(jsonPath("$.newBookings").value(1))
                .andExpect(jsonPath("$.confirmedBookings").value(2))
                .andExpect(jsonPath("$.revenue").value(15000.00));

        mockMvc.perform(get("/api/dashboard/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void performanceScopesToCallerAndRollsUpForManagers() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String meera = salesToken("meera", "meera@securetravels.in");

        UUID raviId = userRepository.findByEmailIgnoreCase("ravi@securetravels.in").orElseThrow().getId();
        UUID meeraId = userRepository.findByEmailIgnoreCase("meera@securetravels.in").orElseThrow().getId();
        seedLead(raviId, Lead.Status.NEW, null);
        seedLead(meeraId, Lead.Status.INTERESTED, null);

        taskRepository.save(new Task(null, raviId, Task.Type.FOLLOW_UP_1D, Instant.now(), null));
        taskRepository.save(new Task(null, raviId, Task.Type.FOLLOW_UP_3D, Instant.now(), null));
        List<Task> raviTasks = taskRepository.findAll().stream().filter(t -> raviId.equals(t.getAssigneeId())).toList();
        raviTasks.forEach(t -> t.complete(Instant.now()));
        taskRepository.saveAll(raviTasks);

        String tripId = createTrip(manager, "Valley of Flowers");
        String batchId = createBatch(manager, tripId, "2026-09-20", 20);
        String b1 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000003");
        String b2 = createBookingFromCustomer(meera, tripId, batchId, 1, "9862000004");
        confirm(manager, b1);
        confirm(manager, b2);

        String p1 = recordPayment(ravi, b1, 10000, "ADVANCE", today(4));
        complete(ravi, p1);
        String p2 = recordPayment(meera, b2, 20000, "ADVANCE", today(4));
        complete(meera, p2);

        String month = YearMonth.now().toString();

        // sales caller -> own row only
        mockMvc.perform(get("/api/dashboard/performance").param("month", month)
                        .header("Authorization", authHeader(ravi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees", hasSize(1)))
                .andExpect(jsonPath("$.employees[0].fullName").value("ravi"))
                .andExpect(jsonPath("$.employees[0].leadsAssigned").value(1))
                .andExpect(jsonPath("$.employees[0].followUpsCompleted").value(2))
                .andExpect(jsonPath("$.employees[0].bookingsClosed").value(1))
                .andExpect(jsonPath("$.employees[0].paymentsCount").value(1))
                .andExpect(jsonPath("$.employees[0].revenue").value(10000.00));

        // manager -> all rows + totals
        mockMvc.perform(get("/api/dashboard/performance").param("month", month)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees", hasSize(2)))
                .andExpect(jsonPath("$.totals.leadsAssigned").value(2))
                .andExpect(jsonPath("$.totals.followUpsCompleted").value(2))
                .andExpect(jsonPath("$.totals.bookingsClosed").value(2))
                .andExpect(jsonPath("$.totals.revenue").value(30000.00));
    }

    @Test
    void targetsUpsertProgressAndRoleGates() throws Exception {
        String manager = managerToken();
        String ravi = salesToken("ravi", "ravi@securetravels.in");
        String meera = salesToken("meera", "meera@securetravels.in");
        String ops = opsToken();

        String month = YearMonth.now().toString();
        UUID raviId = userRepository.findByEmailIgnoreCase("ravi@securetravels.in").orElseThrow().getId();

        // empty month
        mockMvc.perform(get("/api/targets").param("month", month)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets", hasSize(0)))
                .andExpect(jsonPath("$.overall.targetBookings").value(0));

        // company-wide revenue target
        String company = putTarget(manager, null, 0, new BigDecimal("500000.00"), month);
        UUID companyId = UUID.fromString(company);

        // per-sales booking target
        String raviTargetId = putTarget(manager, raviId, 3, new BigDecimal("150000.00"), month);

        // sales/ops cannot write
        mockMvc.perform(put("/api/targets")
                        .header("Authorization", authHeader(ravi))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(raviId.toString(), 2, "100000.00", month)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/targets")
                        .header("Authorization", authHeader(ops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(raviId.toString(), 2, "100000.00", month)))
                .andExpect(status().isForbidden());

        // a confirmed booking in the month moves achievement to 33%
        String tripId = createTrip(manager, "Hampta Pass");
        String batchId = createBatch(manager, tripId, "2026-09-25", 20);
        String b1 = createBookingFromCustomer(ravi, tripId, batchId, 1, "9862000005");
        confirm(manager, b1);

        mockMvc.perform(get("/api/targets").param("month", month)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets", hasSize(2)))
                .andExpect(jsonPath("$.overall.achievedBookings").value(1))
                .andExpect(jsonPath("$.targets[?(@.userId == '%s')].achievedBookings".formatted(raviId), hasSize(1)))
                .andExpect(jsonPath("$.targets[?(@.userId == '%s')].bookingsPct".formatted(raviId), hasSize(1)));

        // upsert updates the same row
        String reUpserted = putTarget(manager, raviId, 4, new BigDecimal("200000.00"), month);
        org.junit.jupiter.api.Assertions.assertEquals(raviTargetId, reUpserted);

        // progress view exposes per-employee + overall
        mockMvc.perform(get("/api/targets/progress").param("month", month)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.employees", hasSize(1)))
                .andExpect(jsonPath("$.overall.targetRevenue").value(500000.00))
                .andExpect(jsonPath("$.overall.achievedBookings").value(1));

        // invalid inputs
        mockMvc.perform(put("/api/targets")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null, 0, "0.00", month)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/targets")
                        .header("Authorization", authHeader(manager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(null, 1, "1000.00", "not-a-month")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/targets/{id}", UUID.randomUUID())
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isNotFound());

        // delete company target; overall falls back to per-user sum
        mockMvc.perform(delete("/api/targets/{id}", companyId)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/targets").param("month", month)
                        .header("Authorization", authHeader(manager)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targets", hasSize(1)))
                .andExpect(jsonPath("$.overall.targetBookings").value(4))
                .andExpect(jsonPath("$.overall.targetRevenue").value(200000.00));

        // sales can still read
        mockMvc.perform(get("/api/targets").param("month", month)
                        .header("Authorization", authHeader(meera)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ helpers

    private void seedLead(UUID ownerId, Lead.Status status, LocalDate followUp) {
        Lead l = new Lead();
        l.setCustomerName("P " + UUID.randomUUID());
        l.setMobileNumber("98620" + (1000 + (int) (Math.random() * 9000)));
        l.setMobileDigits("9");
        l.setSource(Lead.Source.WEBSITE);
        l.setOwnerId(ownerId);
        l.setStatus(status);
        l.setFollowUpDate(followUp);
        l.setConsentGiven(true);
        l.setCreatedBy(ownerId);
        leadRepository.save(l);
    }

    private String putTarget(String token, UUID userId, int bookings, BigDecimal revenue, String month)
            throws Exception {
        MvcResult res = mockMvc.perform(put("/api/targets")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(upsertBody(userId == null ? null : userId.toString(), bookings,
                                revenue.toPlainString(), month)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.month").value(YearMonth.now().atDay(1).toString()))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();
    }

    private String upsertBody(String userId, int bookings, String revenue, String month) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("month", month);
        body.put("targetBookings", bookings);
        body.put("targetRevenue", revenue);
        return objectMapper.writeValueAsString(body);
    }

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

    private void confirm(String token, String bookingId) throws Exception {
        mockMvc.perform(patch("/api/bookings/{id}/status", bookingId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"CONFIRMED\"}"))
                .andExpect(status().isOk());
    }

    private String recordPayment(String token, String bookingId, int amount, String amountType, String dueDate)
            throws Exception {
        MvcResult res = mockMvc.perform(post("/api/payments")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody(bookingId, amount, amountType, dueDate)))
                .andExpect(status().isCreated())
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
                        .content("{\"status\": \"COMPLETED\", \"paidAt\": \"" + Instant.now()
                                + "\", \"gatewayRef\": \"TXN" + UUID.randomUUID().toString().substring(0, 12) + "\"}"))
                .andExpect(status().isOk());
    }

    private String today(int offset) {
        return LocalDate.now().plusDays(offset).toString();
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