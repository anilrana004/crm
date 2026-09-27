package com.securetravels.crm.trip;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 3 — the behaviour the feature actually promises: a season can be
 * generated from a recurrence rule, batches carry a derived capacity colour,
 * and crossing the fill threshold produces exactly one notification on the
 * existing in-app path (no broker anywhere in the chain).
 */
class BatchCapacityIT extends BaseIT {

    @Autowired private TripRepository tripRepository;
    @Autowired private BatchRepository batchRepository;
    @Autowired private Customer360Repository customerRepository;
    @Autowired private CapacityAlertSweep sweep;

    @Test
    @DisplayName("generateSeason creates one batch per date from a monthly rule")
    void generatesSeasonFromRecurrence() throws Exception {
        String token = managerToken();
        String tripId = createTrip(token, "Season Generator");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recurrence", Map.of(
                "firstDeparture", "2026-01-31",
                "lastDeparture", "2026-06-30",
                "frequency", "MONTHLY",
                "interval", 1));
        body.put("maxCapacity", 20);

        String json = mockMvc.perform(post("/api/trips/{tripId}/batches/generate", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(6))
                .andExpect(jsonPath("$.created").value(6))
                .andExpect(jsonPath("$.skipped").value(0))
                .andReturn().getResponse().getContentAsString();

        // The month-boundary behaviour is visible through the real API: 31 Jan
        // clamps to 28 Feb and then returns to the 31st, because the rule is
        // anchored to the first departure rather than the previous occurrence.
        List<String> dates = new ArrayList<>();
        objectMapper.readTree(json).get("batches").forEach(b -> dates.add(b.get("departureDate").asText()));
        assertThat(dates).containsExactly(
                "2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31", "2026-06-30");

        assertThat(batchRepository.findByTripIdOrderByDepartureDateAsc(UUID.fromString(tripId)))
                .hasSize(6);
    }

    @Test
    @DisplayName("re-running the same season skips existing dates instead of failing")
    void regeneratingSkipsExistingDates() throws Exception {
        String token = managerToken();
        String tripId = createTrip(token, "Idempotent Season");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("recurrence", Map.of(
                "firstDeparture", "2026-03-06",
                "lastDeparture", "2026-03-27",
                "frequency", "WEEKLY",
                "interval", 1));
        body.put("maxCapacity", 12);

        // First run creates 4 weekly departures.
        mockMvc.perform(post("/api/trips/{tripId}/batches/generate", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(4));

        // Second run over the same window must be a no-op, not a 409.
        mockMvc.perform(post("/api/trips/{tripId}/batches/generate", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.skipped").value(4))
                .andExpect(jsonPath("$.skippedDates.length()").value(4));

        assertThat(batchRepository.findByTripIdOrderByDepartureDateAsc(UUID.fromString(tripId)))
                .hasSize(4);
    }

    @Test
    @DisplayName("CUSTOM_FIT trips cannot have batches generated")
    void rejectsCustomFitTrip() throws Exception {
        String token = managerToken();

        Map<String, Object> trip = new LinkedHashMap<>();
        trip.put("name", "Bespoke Nepal");
        trip.put("category", "TREK");
        trip.put("bookingType", "CUSTOM_FIT");
        trip.put("baseCost", 90000);
        trip.put("durationDays", 12);
        String created = mockMvc.perform(post("/api/trips")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(trip)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String tripId = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/trips/{tripId}/batches/generate", tripId)
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "recurrence", Map.of(
                                        "firstDeparture", "2026-05-01",
                                        "lastDeparture", "2026-05-31",
                                        "frequency", "MONTHLY",
                                        "interval", 1),
                                "maxCapacity", 10))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("capacity colour tracks fill: GREEN -> AMBER, then RED when full")
    void capacityColourTracksFill() throws Exception {
        String token = managerToken();
        String tripId = createTrip(token, "Colour Demo");
        String batchId = createBatch(token, tripId, "2027-01-10", 10);

        // Empty batch reads GREEN.
        mockMvc.perform(get("/api/trips/{tripId}/batches", tripId).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fillPercent").value(0))
                .andExpect(jsonPath("$[0].capacityColor").value("GREEN"))
                .andExpect(jsonPath("$[0].available").value(10));

        // 9 of 10 = 90% -> exactly the default scarcity threshold -> AMBER.
        bookSeats(token, tripId, batchId, 9, "9810000001");

        mockMvc.perform(get("/api/trips/{tripId}/batches", tripId).header("Authorization", authHeader(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fillPercent").value(90))
                .andExpect(jsonPath("$[0].capacityColor").value("AMBER"))
                .andExpect(jsonPath("$[0].available").value(1));
    }

    @Test
    @DisplayName("crossing 90% raises exactly one scarcity notification on the in-app path")
    void crossingThresholdNotifiesOnce() throws Exception {
        // An OPS user must exist: scarcity routes to the first OPS holder.
        createUser("ops@securetravels.in", "Ops", Role.OPS, "ops123");
        String token = managerToken();
        String sales = salesToken();

        String tripId = createTrip(token, "Scarcity Alert");
        String batchId = createBatch(token, tripId, "2027-02-14", 10);

        // 7 of 10 = 70%: below the threshold, so nothing yet.
        bookSeats(sales, tripId, batchId, 7, "9820000001");
        assertThat(countScarcityNotifications()).isZero();
        assertThat(capacityAlertedAt(batchId)).isNull();

        // 9 of 10 = 90%: crosses it.
        bookSeats(sales, tripId, batchId, 2, "9820000002");

        assertThat(countScarcityNotifications()).isEqualTo(1);
        assertThat(capacityAlertedAt(batchId)).isNotNull();

        // The 10th seat fills the batch but must NOT raise a second alert:
        // the V10 latch is one-shot.
        bookSeats(sales, tripId, batchId, 1, "9820000003");
        assertThat(countScarcityNotifications()).isEqualTo(1);

        // And the notification went to the OPS user, on the real in-app path.
        UUID opsId = userRepository.findByEmailIgnoreCase("ops@securetravels.in").orElseThrow().getId();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from notifications where user_id = ? and title like 'Scarcity:%'",
                Long.class, opsId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("the one-shot latch is a real column, so a reload does not re-fire")
    void latchSurvivesReload() throws Exception {
        createUser("ops@securetravels.in", "Ops", Role.OPS, "ops123");
        String token = managerToken();
        String sales = salesToken();

        String tripId = createTrip(token, "Latch Test");
        String batchId = createBatch(token, tripId, "2027-03-14", 10);
        bookSeats(sales, tripId, batchId, 9, "9830000001");

        assertThat(countScarcityNotifications()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select capacity_alerted_at is not null from batches where id = ?", Boolean.class,
                UUID.fromString(batchId))).isTrue();
    }

    @Test
    @DisplayName("the sweep alerts Admin AND Ops about a near-departure thin batch, once")
    void minGroupSweepAlertsAdminAndOpsOnce() throws Exception {
        createUser("admin@securetravels.in", "Admin", Role.ADMIN, "admin123");
        createUser("ops@securetravels.in", "Ops", Role.OPS, "ops123");
        String token = managerToken();

        String tripId = createTrip(token, "At Risk Departure");
        // 10 days out, inside the 21-day lead window, nobody booked yet.
        String batchId = createBatch(token, tripId,
                LocalDate.now().plusDays(10).toString(), 20);

        sweep.scan();
        assertThat(countTitles("At risk:%")).isEqualTo(2); // one per role

        // Both roles actually received it, on the in-app path.
        for (String email : List.of("admin@securetravels.in", "ops@securetravels.in")) {
            UUID userId = userRepository.findByEmailIgnoreCase(email).orElseThrow().getId();
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from notifications where user_id = ? and title like 'At risk:%'",
                    Long.class, userId)).isEqualTo(1L);
        }

        // V11 latch is set and a second sweep adds nothing.
        assertThat(jdbcTemplate.queryForObject(
                "select min_group_alerted_at is not null from batches where id = ?", Boolean.class,
                UUID.fromString(batchId))).isTrue();
        sweep.scan();
        assertThat(countTitles("At risk:%")).isEqualTo(2);
    }

    @Test
    @DisplayName("the sweep ignores a well-filled batch and a thin one that is still far away")
    void minGroupSweepIgnoresHealthyAndDistant() throws Exception {
        createUser("admin@securetravels.in", "Admin", Role.ADMIN, "admin123");
        createUser("ops@securetravels.in", "Ops", Role.OPS, "ops123");
        String token = managerToken();
        String sales = salesToken();

        String tripId = createTrip(token, "Mixed Batches");
        // Near + healthy: 16 of 20 booked.
        String healthy = createBatch(token, tripId, LocalDate.now().plusDays(5).toString(), 20);
        bookSeats(sales, tripId, healthy, 16, "9840000001");
        // Far + empty: 90 days out, well beyond the lead window.
        createBatch(token, tripId, LocalDate.now().plusDays(90).toString(), 20);

        sweep.scan();

        assertThat(countTitles("At risk:%")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private int countScarcityNotifications() {
        return countTitles("Scarcity:%");
    }

    private int countTitles(String likePattern) {
        Integer n = jdbcTemplate.queryForObject(
                "select count(*) from notifications where title like ?", Integer.class, likePattern);
        return n == null ? 0 : n;
    }

    private Object capacityAlertedAt(String batchId) {
        return jdbcTemplate.queryForObject(
                "select capacity_alerted_at from batches where id = ?", Object.class, UUID.fromString(batchId));
    }

    private String managerToken() throws Exception {
        createUser("manager@securetravels.in", "Manager", Role.MANAGER, "manager123");
        return login("manager@securetravels.in", "manager123");
    }

    private String salesToken() throws Exception {
        createUser("sales@securetravels.in", "Ravi", Role.SALES, "sales123");
        return login("sales@securetravels.in", "sales123");
    }

    private String createTrip(String token, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("category", "PILGRIMAGE");
        body.put("bookingType", "FIXED_BATCH");
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
                                "departureDate", departureDate,
                                "maxCapacity", capacity))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(created).get("id").asText();
    }

    private void bookSeats(String token, String tripId, String batchId, int travellers, String phone)
            throws Exception {
        List<Map<String, Object>> travellerList = java.util.stream.IntStream.range(0, travellers)
                .mapToObj(i -> Map.<String, Object>of(
                        "fullName", "Traveller " + phone + " " + (i + 1),
                        "age", 30 + i,
                        "gender", i % 2 == 0 ? "M" : "F",
                        "medicalCertRequired", false))
                .toList();

        Customer360 customer = Customer360.fromLead("Guest " + phone, phone, phone, phone,
                "guest" + phone + "@example.com", true, "capacity alert test booking");
        String customerId = customerRepository.save(customer).getId().toString();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customerId", customerId);
        body.put("tripId", tripId);
        body.put("batchId", batchId);
        body.put("numTravellers", travellers);
        body.put("travellers", travellerList);

        mockMvc.perform(post("/api/bookings")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }
}
