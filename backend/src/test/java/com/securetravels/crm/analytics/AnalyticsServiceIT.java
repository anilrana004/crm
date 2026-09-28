package com.securetravels.crm.analytics;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.analytics.dto.AuditSearchResponse;
import com.securetravels.crm.analytics.dto.CustomerInsightsResponse;
import com.securetravels.crm.analytics.dto.OperationsReadinessResponse;
import com.securetravels.crm.analytics.dto.ReportFilter;
import com.securetravels.crm.analytics.dto.SalesFunnelResponse;
import com.securetravels.crm.analytics.dto.TeamPerformanceResponse;
import com.securetravels.crm.analytics.dto.TripPerformanceResponse;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the Module 2 SQL against real Postgres.
 *
 * <p>These exist because the queries are hand-written aggregations over eight
 * tables with optional filters, and a mistake in any of them shows up only at
 * execution time. Each test asserts the SHAPE of the answer as well as its
 * content, so a report that returns plausible-looking nonsense fails.
 *
 * <p>Rows are seeded with SQL rather than through the entities. The subject here
 * is the query, and going through the booking service would drag seat holds,
 * capacity checks and audit writes into a test about aggregation.
 */
class AnalyticsServiceIT extends BaseIT {

    @Autowired
    private AnalyticsService service;

    private static final LocalDate TRAVEL = LocalDate.of(2026, 3, 14);   // SPRING
    private static final LocalDate OUT_OF_SEASON = LocalDate.of(2026, 7, 14);   // MONSOON

    private static final java.time.ZoneId ZONE = java.time.ZoneId.systemDefault();

    /**
     * JdbcTemplate cannot infer a SQL type for {@link Instant} and throws
     * "Can't infer the SQL type to use for an instance of java.time.Instant", so
     * every timestamp is bound as a {@link java.sql.Timestamp}.
     */
    private static java.sql.Timestamp ts(LocalDate day, int hours) {
        return java.sql.Timestamp.valueOf(day.atTime(hours, 0));
    }

    private UserPrincipal principal(UUID id, Role role) {
        return new UserPrincipal(id, "a@b.c", "A", role, true);
    }

    // ------------------------------------------------------------------ seeding

    private UUID seedTrip(String name, int durationDays, String baseCost) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into trips (id, name, slug, category, booking_type, base_cost, duration_days, is_active)
                values (?,?,?,?,?,?,?,true)""",
                id, name, name.toLowerCase().replace(' ', '-'), "TREK", "FIXED_BATCH",
                new BigDecimal(baseCost), durationDays);
        return id;
    }

    private UUID seedBatch(UUID tripId, LocalDate departure, int capacity, int seats) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into batches (id, trip_id, departure_date, max_capacity, seats_booked, status)
                values (?,?,?,?,?, 'OPEN')""", id, tripId, departure, capacity, seats);
        return id;
    }

    private UUID seedCustomer(String name, String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into customer360 (id, full_name, mobile_number, mobile_digits, email, total_spent)
                values (?,?,?,?,?, 0.00)""", id, name, "9800000000", "9800000000", email);
        return id;
    }

    private UUID seedLead(String name, String email, String source, String status, UUID ownerId, LocalDate created) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into leads (id, customer_name, mobile_number, mobile_digits, email,
                                   source, status, owner_id, destination, created_at, updated_at, version)
                values (?,?,?,?,?,?,?,?,?,?, now(), 0)""",
                id, name, "9800000001", "9800000001", email, source, status, ownerId,
                "Annapurna", ts(created, 0));
        return id;
    }

    private UUID seedBooking(UUID tripId, UUID batchId, UUID customerId, UUID leadId,
                             LocalDate travelDate, String status, String total, int travellers) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into bookings (id, booking_ref, trip_id, batch_id, customer_id, lead_id,
                                     booking_type, num_travellers, travel_date, status,
                                     total_amount, discount_amount, tax_amount,
                                     created_at, updated_at, version)
                values (?,?,?,?,?,?, 'FIXED_BATCH', ?,?,?, ?, 0.00, 0.00, now(), now(), 0)""",
                id, "BK" + Math.abs(id.hashCode() % 100000), tripId, batchId, customerId, leadId,
                travellers, travelDate, status, new BigDecimal(total));
        return id;
    }

    private void seedLedgerCredit(UUID bookingId, UUID leadId, UUID consultantId, UUID tripId,
                                  java.sql.Timestamp credited, String net) {
        jdbcTemplate.update("""
                insert into sales_commission_ledger
                    (booking_id, lead_id, consultant_id, trip_id, credited_at,
                     booking_status_at_credit, gross_amount, discount_amount, tax_amount, net_amount)
                values (?,?,?,?,?, 'CONFIRMED', ?, 0.00, 0.00, ?)""",
                bookingId, leadId, consultantId, tripId, credited, new BigDecimal(net), new BigDecimal(net));
    }

    // ------------------------------------------------------------------ 1. funnel

    @Test
    @DisplayName("funnel counts a lead at the stage it REACHED, even after it moved backwards")
    void funnelCountsReachedStagesNotJustCurrentStatus() {
        UUID owner = createUser("regressed@x.in", "Regressed Owner", Role.SALES, "pw123456");
        // Currently INTERESTED, but audit_log shows it previously reached QUOTATION_SENT.
        UUID lead = seedLead("Ramesh New", "Ramesh.New@x.com", "INSTAGRAM",
                "INTERESTED", owner, TRAVEL.minusDays(10));
        jdbcTemplate.update("""
                insert into audit_log (id, entity, entity_id, action, field, old_value, new_value, created_at)
                values (?, 'LEAD', ?, 'UPDATE', 'status', 'INTERESTED', 'QUOTATION_SENT', now())""",
                UUID.randomUUID(), lead);

        UserPrincipal caller = principal(owner, Role.SALES);
        SalesFunnelResponse f = service.funnel(ReportFilter.none(), caller);

        SalesFunnelResponse.Stage quotation = f.stages().stream()
                .filter(s -> s.code().equals("QUOTATION_SENT")).findFirst().orElseThrow();
        SalesFunnelResponse.Stage interested = f.stages().stream()
                .filter(s -> s.code().equals("INTERESTED")).findFirst().orElseThrow();

        assertThat(quotation.reached())
                .as("the lead reached quotation at some point, so it counts there")
                .isEqualTo(1);
        assertThat(interested.reached()).isEqualTo(1);
        assertThat(interested.currentlyHere())
                .as("but it is currently sitting back at INTERESTED")
                .isEqualTo(1);
        assertThat(quotation.currentlyHere()).isEqualTo(0);
    }

    @Test
    @DisplayName("funnel stage counts are monotonically non-increasing")
    void funnelStagesAreMonotonic() {
        UUID owner = createUser("mono@x.in", "Mono", Role.SALES, "pw123456");
        seedLead("A", "a@x.com", "WALK_IN", "NEW", owner, TRAVEL.minusDays(5));
        seedLead("B", "b@x.com", "WALK_IN", "INTERESTED", owner, TRAVEL.minusDays(4));
        seedLead("C", "c@x.com", "INSTAGRAM", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(3));
        seedLead("D", "d@x.com", "INSTAGRAM", "LOST", owner, TRAVEL.minusDays(2));

        SalesFunnelResponse f = service.funnel(ReportFilter.none(), principal(owner, Role.SALES));

        long previous = Long.MAX_VALUE;
        for (SalesFunnelResponse.Stage s : f.stages()) {
            assertThat(s.reached())
                    .as("stage %s reached count must not exceed the previous stage", s.code())
                    .isLessThanOrEqualTo(previous);
            previous = s.reached();
        }
        assertThat(f.totals().leads()).isEqualTo(4);
        assertThat(f.totals().lost()).isEqualTo(1);
    }

    @Test
    @DisplayName("a LOST lead still counts toward the stages it passed through")
    void lostLeadStillCountsAsHavingReachedQuotation() {
        UUID owner = createUser("lost@x.in", "Lost", Role.SALES, "pw123456");
        UUID lead = seedLead("E", "e@x.com", "REFERRAL", "LOST", owner, TRAVEL.minusDays(9));
        jdbcTemplate.update("""
                insert into audit_log (id, entity, entity_id, action, field, old_value, new_value, created_at)
                values (?, 'LEAD', ?, 'UPDATE', 'status', 'INTERESTED', 'QUOTATION_SENT', now())""",
                UUID.randomUUID(), lead);

        SalesFunnelResponse f = service.funnel(ReportFilter.none(), principal(owner, Role.SALES));

        assertThat(f.stages().stream().filter(s -> s.code().equals("QUOTATION_SENT"))
                .findFirst().orElseThrow().reached()).isEqualTo(1);
        assertThat(f.totals().booked())
                .as("LOST is an outcome, not a stage, so it must not count as booked")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("an empty scope reports null percentages, not 0% of nothing")
    void emptyScopeDoesNotReportZeroPercent() {
        UUID owner = createUser("empty@x.in", "Empty", Role.SALES, "pw123456");
        SalesFunnelResponse f = service.funnel(ReportFilter.none(), principal(owner, Role.SALES));

        assertThat(f.totals().leads()).isZero();
        assertThat(f.totals().overallConversionPct())
                .as("'0% of zero leads booked' and 'no leads in scope' are different facts")
                .isNull();
    }

    @Test
    @DisplayName("the season filter selects by month of the lead's own date")
    void seasonFilterNarrowsTheWindow() {
        UUID owner = createUser("season@x.in", "Season", Role.SALES, "pw123456");
        seedLead("Spring", "sp@x.com", "WEBSITE", "NEW", owner, TRAVEL);                       // March
        seedLead("Monsoon", "mo@x.com", "WEBSITE", "NEW", owner, OUT_OF_SEASON);              // July

        SalesFunnelResponse spring = service.funnel(
                new ReportFilter(null, null, null, null, Season.SPRING, null), principal(owner, Role.SALES));
        SalesFunnelResponse monsoon = service.funnel(
                new ReportFilter(null, null, null, null, Season.MONSOON, null), principal(owner, Role.SALES));

        assertThat(spring.totals().leads()).isEqualTo(1);
        assertThat(monsoon.totals().leads()).isEqualTo(1);
        assertThat(spring.bySource()).extracting(SalesFunnelResponse.SourceBreakdown::leads)
                .containsExactly(1L);
    }

    // ------------------------------------------------------------------ 2. trips

    @Test
    @DisplayName("trip performance computes fill rate and live revenue")
    void tripPerformanceComputesFillRateAndRevenue() {
        UUID owner = createUser("trip@x.in", "Trip Owner", Role.SALES, "pw123456");
        UUID trip = seedTrip("Annapurna Test", 7, "20000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Cust One", "c1@x.com");
        UUID lead = seedLead("Lead One", "l1@x.com", "WEBSITE", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(20));
        seedBooking(trip, batch, customer, lead, TRAVEL, "CONFIRMED", "100000.00", 2);

        jdbcTemplate.update("update batches set seats_booked = 2 where id = ?", batch);
        seedLedgerCredit(jdbcTemplate.queryForObject(
                        "select id from bookings where customer_id = ?", UUID.class, customer),
                lead, owner, trip, ts(TRAVEL, 0), "100000.00");

        TripPerformanceResponse r = service.tripPerformance(ReportFilter.none(), principal(owner, Role.SALES));
        TripPerformanceResponse.Trip t = r.trips().stream()
                .filter(x -> x.tripId().equals(trip)).findFirst().orElseThrow();

        assertThat(t.bookings()).isEqualTo(1);
        assertThat(t.pax()).isEqualTo(2);
        assertThat(t.maxCapacity()).isEqualTo(10);
        assertThat(t.fillRatePct()).isEqualByComparingTo("20.0");
        assertThat(t.revenue()).isEqualByComparingTo("100000.00");
        assertThat(t.avgBookingValue()).isEqualByComparingTo("100000.00");
        // revenue 100000 - (base_cost 20000 * 2 pax)
        assertThat(t.revenueLessBaseCost()).isEqualByComparingTo("60000.00");
    }

    @Test
    @DisplayName("a cancelled booking is excluded from revenue but its seats are not counted")
    void cancelledBookingIsExcludedFromRevenue() {
        UUID owner = createUser("cancel@x.in", "Cancel Owner", Role.SALES, "pw123456");
        UUID trip = seedTrip("Cancel Trip", 5, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Cust C", "c3@x.com");
        UUID lead = seedLead("Lead C", "l3@x.com", "WEBSITE", "LOST", owner, TRAVEL.minusDays(20));
        seedBooking(trip, batch, customer, lead, TRAVEL, "CANCELLED", "50000.00", 2);

        TripPerformanceResponse r = service.tripPerformance(ReportFilter.none(), principal(owner, Role.OPS));
        TripPerformanceResponse.Trip t = r.trips().stream()
                .filter(x -> x.tripId().equals(trip)).findFirst().orElseThrow();

        assertThat(t.revenue()).isEqualByComparingTo("0.00");
        assertThat(t.seatsBooked()).isEqualTo(0);
        assertThat(t.bookings())
                .as("the booking still exists, it is just not revenue or occupancy")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the cost basis is returned to the caller and admits it is not a margin")
    void costBasisIsExplicit() {
        UUID owner = createUser("basis@x.in", "Basis", Role.SALES, "pw123456");
        TripPerformanceResponse r = service.tripPerformance(ReportFilter.none(), principal(owner, Role.SALES));
        assertThat(r.costBasis().isPartialCost()).isTrue();
        assertThat(r.costBasis().note()).contains("NOT a margin");
    }

    // ------------------------------------------------------------------ 3. team

    @Test
    @DisplayName("team revenue comes from the ledger and ignores revoked credits")
    void teamRevenueExcludesRevokedCredits() {
        UUID owner = createUser("team@x.in", "Team Owner", Role.SALES, "pw123456");
        UUID trip = seedTrip("Team Trip", 6, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Cust T", "ct@x.com");
        UUID leadA = seedLead("Lead A", "la@x.com", "WEBSITE", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(20));
        UUID leadB = seedLead("Lead B", "lb@x.com", "WEBSITE", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(20));

        UUID kept = seedBooking(trip, batch, customer, leadA, TRAVEL, "CONFIRMED", "100000.00", 1);
        UUID revoked = seedBooking(trip, batch, customer, leadB, TRAVEL, "CONFIRMED", "50000.00", 1);

        seedLedgerCredit(kept, leadA, owner, trip, ts(TRAVEL, 0), "100000.00");
        seedLedgerCredit(revoked, leadB, owner, trip, ts(TRAVEL, 0), "50000.00");
        jdbcTemplate.update("update sales_commission_ledger set revoked_at = now() where booking_id = ?", revoked);

        TeamPerformanceResponse r = service.teamPerformance(ReportFilter.none(), principal(owner, Role.SALES));
        TeamPerformanceResponse.Consultant c = r.consultants().stream()
                .filter(x -> x.consultantId().equals(owner)).findFirst().orElseThrow();

        assertThat(c.bookingsCredited()).isEqualTo(1);
        assertThat(c.creditsRevoked()).isEqualTo(1);
        assertThat(c.revenue())
                .as("the revoked credit must not appear in revenue")
                .isEqualByComparingTo("100000.00");
    }

    @Test
    @DisplayName("SLA compliance ignores tasks that were never given a deadline")
    void slaComplianceExcludesTasksWithNoDeadline() {
        UUID owner = createUser("sla@x.in", "Sla Owner", Role.SALES, "pw123456");
        // One task met, one task missed, one with no SLA deadline at all.
        seedTask(owner, "INITIAL_CALL", TRAVEL, true, true);
        seedTask(owner, "INITIAL_CALL", TRAVEL, true, false);
        seedTask(owner, "INITIAL_CALL", TRAVEL, true, true, false);

        TeamPerformanceResponse r = service.teamPerformance(ReportFilter.none(), principal(owner, Role.SALES));
        TeamPerformanceResponse.Consultant c = r.consultants().stream()
                .filter(x -> x.consultantId().equals(owner)).findFirst().orElseThrow();

        assertThat(c.slaTasksDue())
                .as("the task with no sla_deadline is not a due task")
                .isEqualTo(2);
        assertThat(c.slaTasksMet()).isEqualTo(1);
        assertThat(c.slaCompliancePct()).isEqualByComparingTo("50.0");
        assertThat(r.slaBasis().onlyTasksWithSlaDeadline()).isTrue();
    }

    private void seedTask(UUID assignee, String type, LocalDate due,
                         boolean metSla, boolean completed) {
        seedTask(assignee, type, due, metSla, completed, true);
    }

    /**
     * @param withSlaDeadline when false the task is written with a null
     *                        sla_deadline, which is the case the compliance
     *                        percentage has to exclude.
     */
    private void seedTask(UUID assignee, String type, LocalDate due,
                          boolean metSla, boolean completed, boolean withSlaDeadline) {
        java.sql.Timestamp deadline = withSlaDeadline ? ts(due, 1) : null;
        java.sql.Timestamp done = completed && deadline != null
                ? (metSla ? new java.sql.Timestamp(deadline.getTime() - 60_000L)
                          : new java.sql.Timestamp(deadline.getTime() + 60_000L))
                : null;
        jdbcTemplate.update("""
                insert into tasks (id, assignee_id, type, status, due_at, sla_deadline, completed_at, created_at)
                values (?,?,?, 'COMPLETED', ?, ?, ?, now())""",
                UUID.randomUUID(), assignee, type, ts(due, 0), deadline, done);
    }

    // ------------------------------------------------------------------ 4. operations

    @Test
    @DisplayName("incident summary reports UNAVAILABLE, never zero incidents")
    void incidentSummaryIsUnavailableNotZero() {
        UUID owner = createUser("ops@x.in", "Ops", Role.OPS, "pw123456");
        OperationsReadinessResponse r =
                service.operationsReadiness(ReportFilter.none(), principal(owner, Role.OPS));

        assertThat(r.incidentSummary().available())
                .as("Module 1 is not built, so there is no incident data at all")
                .isFalse();
        assertThat(r.incidentSummary().openIncidents())
                .as("reporting 0 would assert nothing has ever gone wrong, backed by no data")
                .isNull();
        assertThat(r.incidentSummary().blockedBy()).contains("Module 1");
    }

    @Test
    @DisplayName("batch readiness lists concrete gaps instead of a boolean")
    void batchReadinessNamesTheGaps() {
        UUID owner = createUser("gap@x.in", "Gap", Role.OPS, "pw123456");
        UUID trip = seedTrip("Gap Trip", 5, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 4, 4);
        UUID customer = seedCustomer("Cust G", "cg@x.com");
        UUID booking = seedBooking(trip, batch, customer, null, TRAVEL, "CONFIRMED", "40000.00", 1);
        seedHandoff(booking, batch, trip, "PENDING", "CONFIRMED");

        OperationsReadinessResponse r =
                service.operationsReadiness(ReportFilter.none(), principal(owner, Role.OPS));
        OperationsReadinessResponse.BatchReadiness b = r.batches().stream()
                .filter(x -> x.batchId().equals(batch)).findFirst().orElseThrow();

        assertThat(b.gaps()).anyMatch(g -> g.contains("hotel not confirmed"));
        assertThat(b.tripSheetGenerated()).isFalse();
        assertThat(b.gaps()).anyMatch(g -> g.contains("trip sheet"));
    }

    private void seedHandoff(UUID bookingId, UUID batchId, UUID tripId, String hotel, String transport) {
        jdbcTemplate.update("""
                insert into operations_handoffs
                    (id, booking_id, batch_id, ops_ref, travel_date, pax,
                     hotel_status, transport_status, payment_status)
                values (?,?,?,?,?,1,?,?, 'PENDING')""",
                UUID.randomUUID(), bookingId, batchId, "OPS-1", TRAVEL, hotel, transport);
    }

    @Test
    @DisplayName("vendor scorecards are labelled as completion, not reliability")
    void vendorScorecardIsNotCalledReliability() {
        UUID owner = createUser("vendor@x.in", "Vendor", Role.OPS, "pw123456");
        UUID vendorId = UUID.randomUUID();
        jdbcTemplate.update("insert into vendors (id, name, category, is_active) values (?,?,?,true)",
                vendorId, "Everest Guides", "GUIDE");

        UUID trip = seedTrip("Vendor Trip", 5, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Cust V", "cv@x.com");
        UUID booking = seedBooking(trip, batch, customer, null, TRAVEL, "CONFIRMED", "40000.00", 1);
        seedHandoffWithGuide(booking, batch, trip, vendorId);

        OperationsReadinessResponse r =
                service.operationsReadiness(ReportFilter.none(), principal(owner, Role.OPS));
        OperationsReadinessResponse.VendorScorecard v = r.vendors().stream()
                .filter(x -> x.vendorId().equals(vendorId)).findFirst().orElseThrow();

        assertThat(v.isReliabilityScore())
                .as("with no incident data this is a completion rate and must not claim otherwise")
                .isFalse();
        assertThat(v.handoffsAssigned()).isEqualTo(1);
        assertThat(v.scoreBasis()).contains("handoff assignment");
    }

    private void seedHandoffWithGuide(UUID bookingId, UUID batchId, UUID tripId, UUID guideId) {
        jdbcTemplate.update("""
                insert into operations_handoffs
                    (id, booking_id, batch_id, ops_ref, travel_date, pax,
                     hotel_status, transport_status, payment_status, guide_id)
                values (?,?,?,?,?,1, 'CONFIRMED', 'CONFIRMED', 'PENDING', ?)""",
                UUID.randomUUID(), bookingId, batchId, "OPS-2", TRAVEL, guideId);
    }

    // ------------------------------------------------------------------ 5. customers

    @Test
    @DisplayName("lifetime value is computed from bookings, ignoring the stale total_spent column")
    void lifetimeValueIsComputedLive() {
        UUID owner = createUser("ltv@x.in", "Ltv", Role.SALES, "pw123456");
        UUID trip = seedTrip("Ltv Trip", 5, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Valued Cust", "vc@x.com");
        UUID lead = seedLead("Ltv Lead", "ll@x.com", "WEBSITE", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(20));
        seedBooking(trip, batch, customer, lead, TRAVEL, "CONFIRMED", "175000.00", 2);
        seedLedgerCredit(jdbcTemplate.queryForObject(
                        "select id from bookings where customer_id = ?", UUID.class, customer),
                lead, owner, trip, ts(TRAVEL, 0), "175000.00");

        // The denormalised column that the module deliberately refuses to read.
        assertThat(jdbcTemplate.queryForObject(
                "select total_spent from customer360 where id = ?", BigDecimal.class, customer))
                .isEqualByComparingTo("0.00");

        CustomerInsightsResponse r = service.customerInsights(ReportFilter.none(), principal(owner, Role.SALES));
        CustomerInsightsResponse.Customer c = r.customers().stream()
                .filter(x -> x.customerId().equals(customer)).findFirst().orElseThrow();

        assertThat(c.lifetimeValue())
                .as("must be 175000 from the booking, not 0 from total_spent")
                .isEqualByComparingTo("175000.00");
        assertThat(r.valueBasis().liveComputation()).isTrue();
    }

    @Test
    @DisplayName("repeat customers are identified from booking count")
    void repeatCustomersAreDetected() {
        UUID owner = createUser("rep@x.in", "Rep", Role.SALES, "pw123456");
        UUID trip = seedTrip("Rep Trip", 5, "10000.00");
        UUID batch = seedBatch(trip, TRAVEL, 10, 0);
        UUID customer = seedCustomer("Repeat Cust", "rc@x.com");
        UUID lead = seedLead("Rep Lead", "rl@x.com", "WEBSITE", "BOOKING_CONFIRMED", owner, TRAVEL.minusDays(20));
        seedBooking(trip, batch, customer, lead, TRAVEL, "COMPLETED", "50000.00", 1);
        seedBooking(trip, batch, customer, lead, TRAVEL, "COMPLETED", "60000.00", 1);
        seedLedgerCredit(jdbcTemplate.queryForObject(
                        "select id from bookings where customer_id = ? limit 1", UUID.class, customer),
                lead, owner, trip, ts(TRAVEL, 0), "50000.00");

        CustomerInsightsResponse r = service.customerInsights(ReportFilter.none(), principal(owner, Role.SALES));
        CustomerInsightsResponse.Customer c = r.customers().stream()
                .filter(x -> x.customerId().equals(customer)).findFirst().orElseThrow();

        assertThat(c.bookings()).isEqualTo(2);
        assertThat(c.repeat()).isTrue();
        assertThat(c.lifetimeValue()).isEqualByComparingTo("110000.00");
    }

    // ------------------------------------------------------------------ 6. audit search

    @Test
    @DisplayName("audit search finds a row by ONE PART of a stored email address")
    void auditSearchFindsPartialEmail() {
        UUID owner = createUser("fts@x.in", "Fts", Role.ADMIN, "pw123456");
        UUID entity = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into audit_log (id, entity, entity_id, action, field, old_value, new_value,
                                       actor_id, created_at)
                values (?, 'LEAD', ?, 'UPDATE', 'email', 'old@x.com', 'Ramesh.New@x.com', ?, now())""",
                UUID.randomUUID(), entity, owner);

        AuditSearchResponse r = service.searchAudit("ramesh", null, null, null, null, 0, 25,
                principal(owner, Role.ADMIN));

        assertThat(r.mode()).isEqualTo(AuditSearchResponse.SearchMode.TEXT);
        assertThat(r.hits())
                .as("the default parser keeps an email as one lexeme, so this only works "
                        + "because the generated tsvector is split on non-alphanumerics")
                .hasSize(1);
        assertThat(r.hits().get(0).matchedBy()).isEqualTo("fts");
        assertThat(r.hits().get(0).newValue()).isEqualTo("Ramesh.New@x.com");
    }

    @Test
    @DisplayName("audit search survives a typo via trigram similarity")
    void auditSearchToleratesTypos() {
        UUID owner = createUser("tri@x.in", "Tri", Role.ADMIN, "pw123456");
        UUID entity = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into audit_log (id, entity, entity_id, action, field, old_value, new_value,
                                       actor_id, created_at)
                values (?, 'LEAD', ?, 'UPDATE', 'fullName', 'old', 'Ramesh Bahadur', ?, now())""",
                UUID.randomUUID(), entity, owner);

        AuditSearchResponse r = service.searchAudit("Rmesh Bahadur", null, null, null, null, 0, 25,
                principal(owner, Role.ADMIN));

        assertThat(r.hits())
                .as("tsvector cannot match a near-miss; pg_trgm is what makes this work")
                .hasSize(1);
        assertThat(r.hits().get(0).matchedBy()).isEqualTo("trigram");
    }

    @Test
    @DisplayName("a structured-only search reports mode STRUCTURED, not a text match")
    void structuredSearchIsDistinguishedFromTextSearch() {
        UUID owner = createUser("str@x.in", "Str", Role.ADMIN, "pw123456");
        UUID entity = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into audit_log (id, entity, entity_id, action, field, old_value, new_value,
                                       actor_id, created_at)
                values (?, 'LEAD', ?, 'UPDATE', 'status', 'NEW', 'INTERESTED', ?, now())""",
                UUID.randomUUID(), entity, owner);

        AuditSearchResponse r = service.searchAudit(null, "LEAD", null, null, null, 0, 25,
                principal(owner, Role.ADMIN));

        assertThat(r.mode()).isEqualTo(AuditSearchResponse.SearchMode.STRUCTURED);
        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).matchedBy()).isEqualTo("none");
    }

    @Test
    @DisplayName("audit search pages instead of returning everything")
    void auditSearchPaginates() {
        UUID owner = createUser("page@x.in", "Page", Role.ADMIN, "pw123456");
        for (int i = 0; i < 7; i++) {
            UUID entity = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into audit_log (id, entity, entity_id, action, field, old_value, new_value,
                                           actor_id, created_at)
                    values (?, 'LEAD', ?, 'UPDATE', 'status', 'NEW', 'INTERESTED', ?, now())""",
                    UUID.randomUUID(), entity, owner);
        }

        AuditSearchResponse first = service.searchAudit(null, "LEAD", null, null, null, 0, 3,
                principal(owner, Role.ADMIN));
        AuditSearchResponse second = service.searchAudit(null, "LEAD", null, null, null, 1, 3,
                principal(owner, Role.ADMIN));

        assertThat(first.hits()).hasSize(3);
        assertThat(second.hits()).hasSize(3);
        assertThat(first.hits().get(0).id())
                .as("pages must not repeat rows")
                .isNotEqualTo(second.hits().get(0).id());
    }
}
