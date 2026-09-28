package com.securetravels.crm.analytics;

import com.securetravels.crm.analytics.dto.AuditSearchResponse;
import com.securetravels.crm.analytics.dto.CustomerInsightsResponse;
import com.securetravels.crm.analytics.dto.OperationsReadinessResponse;
import com.securetravels.crm.analytics.dto.ReportFilter;
import com.securetravels.crm.analytics.dto.SalesFunnelResponse;
import com.securetravels.crm.analytics.dto.TeamPerformanceResponse;
import com.securetravels.crm.analytics.dto.TripPerformanceResponse;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.UserPrincipal;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Module 2 reporting queries.
 *
 * <p>Uses {@link NamedParameterJdbcTemplate} rather than JPA: these are
 * aggregations across leads, bookings, batches, tasks and handoffs that have no
 * single aggregate root, so there is nothing to map. Every user-supplied value
 * is a bound parameter; the only string this class ever concatenates into SQL is
 * a column name or a condition chosen from a closed set in this file.
 *
 * <p>Reports read live tables on every call. There is no cache and no
 * pre-aggregated summary, because a stale number in a commission report is worse
 * than a slow one.
 */
@Service
public class AnalyticsService {

    /** Roles that may see every consultant's numbers. */
    private static final List<Role> SEES_ALL =
            List.of(Role.MANAGER, Role.ADMIN, Role.CEO, Role.OPS);

    /**
     * Funnel stage order. LOST is deliberately absent: it is a terminal outcome,
     * not a stage, and counting it as one would make a lost lead look like
     * pipeline depth.
     */
    private static final List<String> FUNNEL_STAGES =
            List.of("NEW", "INTERESTED", "QUOTATION_SENT", "BOOKING_CONFIRMED");

    private static final List<String> FUNNEL_LABELS = List.of(
            "New", "Interested", "Quotation sent", "Booking confirmed");

    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final NamedParameterJdbcTemplate jdbc;

    public AnalyticsService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================================================================
    // Scoping
    // ==================================================================

    /**
     * Overwrite a requested consultant with the caller's own id when the caller
     * is a SALES user.
     *
     * <p>This is the one place in Module 2 where a security decision is made, and
     * it is made in the service rather than only in {@code @PreAuthorize} on the
     * controller. A role check answers "may this role call this endpoint"; it
     * cannot answer "may this role see this consultant's revenue", because the
     * latter depends on a request parameter. Relying on the annotation alone would
     * mean a SALES user reads a team report simply by passing someone else's id.
     */
    ReportFilter scopeToCaller(ReportFilter filter, UserPrincipal caller) {
        if (SEES_ALL.contains(caller.role())) {
            return filter;
        }
        return filter.withConsultant(caller.id());
    }

    private static String scopeLabel(UserPrincipal caller) {
        return SEES_ALL.contains(caller.role()) ? "ALL" : "SELF";
    }

    // ==================================================================
    // 1. Sales Funnel
    // ==================================================================

    /**
     * "Reached stage X" is derived from each lead's current status combined with
     * the LEAD status transitions in {@code audit_log}, taking the furthest of the
     * two. A lead that reached QUOTATION_SENT and later dropped back to INTERESTED
     * still counts as having reached quotation, which is what makes the stage
     * counts monotonically non-increasing.
     *
     * <p>Known limitation: transitions are only in the audit log from the point
     * auditing was switched on. A lead created or imported before that contributes
     * only its current status, so a lead that progressed and regressed entirely
     * before the trail existed is undercounted. There is no lead status history
     * table to recover it from, which is itself the argument for adding one.
     */
    @Transactional(readOnly = true)
    public SalesFunnelResponse funnel(ReportFilter requested, UserPrincipal caller) {
        ReportFilter f = scopeToCaller(requested, caller);

        MapSqlParameterSource p = new MapSqlParameterSource();
        String where = leadWhere(f, "l", p);

        String sql = """
                with scoped as (
                    select l.id, l.status, l.source
                    from leads l
                    %s
                ), ranked as (
                    select s.id, s.status, s.source,
                           case s.status
                               when 'NEW' then 0 when 'INTERESTED' then 1
                               when 'QUOTATION_SENT' then 2 when 'BOOKING_CONFIRMED' then 3
                               when 'LOST' then -1
                           end as current_rank,
                           coalesce((
                               select max(case al.new_value
                                          when 'INTERESTED' then 1
                                          when 'QUOTATION_SENT' then 2
                                          when 'BOOKING_CONFIRMED' then 3
                                          else 0 end)
                               from audit_log al
                               where al.entity = 'LEAD'
                                 and al.entity_id = s.id
                                 and al.field = 'status'
                           ), 0) as ever_rank
                    from scoped s
                )
                select
                    count(*) filter (where greatest(current_rank, ever_rank) >= 0) as r_new,
                    count(*) filter (where greatest(current_rank, ever_rank) >= 1) as r_interested,
                    count(*) filter (where greatest(current_rank, ever_rank) >= 2) as r_quotation,
                    count(*) filter (where greatest(current_rank, ever_rank) >= 3) as r_booked,
                    count(*) filter (where current_rank = 0) as c_new,
                    count(*) filter (where current_rank = 1) as c_interested,
                    count(*) filter (where current_rank = 2) as c_quotation,
                    count(*) filter (where current_rank = 3) as c_booked,
                    count(*) filter (where status = 'LOST') as lost,
                    count(*) as total
                from ranked
                """.formatted(where);

        Map<String, Object> row = single(sql, p);
        long total = asLong(row.get("total"));

        long[] reached = {
                asLong(row.get("r_new")),
                asLong(row.get("r_interested")),
                asLong(row.get("r_quotation")),
                asLong(row.get("r_booked"))
        };
        long[] current = {
                asLong(row.get("c_new")),
                asLong(row.get("c_interested")),
                asLong(row.get("c_quotation")),
                asLong(row.get("c_booked"))
        };

        List<SalesFunnelResponse.Stage> stages = new ArrayList<>();
        for (int i = 0; i < FUNNEL_STAGES.size(); i++) {
            stages.add(new SalesFunnelResponse.Stage(
                    FUNNEL_STAGES.get(i), FUNNEL_LABELS.get(i),
                    reached[i], current[i], pct(reached[i], total)));
        }

        long booked = reached[3];
        long lost = asLong(row.get("lost"));

        List<SalesFunnelResponse.SourceBreakdown> bySource = sourceBreakdown(f, p, total);

        return new SalesFunnelResponse(
                scopeLabel(caller), f, stages,
                new SalesFunnelResponse.Totals(total, booked, lost, pct(booked, total)),
                bySource);
    }

    private List<SalesFunnelResponse.SourceBreakdown> sourceBreakdown(
            ReportFilter f, MapSqlParameterSource baseParams, long total) {
        MapSqlParameterSource p = new MapSqlParameterSource(baseParams.getValues());
        String where = leadWhere(f, "l", p);

        String sql = """
                with scoped as (
                    select l.id, l.status, l.source
                    from leads l
                    %s
                ), ranked as (
                    select s.status, s.source,
                           case s.status
                               when 'NEW' then 0 when 'INTERESTED' then 1
                               when 'QUOTATION_SENT' then 2 when 'BOOKING_CONFIRMED' then 3
                               when 'LOST' then -1
                           end as current_rank,
                           coalesce((
                               select max(case al.new_value
                                          when 'INTERESTED' then 1
                                          when 'QUOTATION_SENT' then 2
                                          when 'BOOKING_CONFIRMED' then 3
                                          else 0 end)
                               from audit_log al
                               where al.entity = 'LEAD'
                                 and al.entity_id = s.id
                                 and al.field = 'status'
                           ), 0) as ever_rank
                    from scoped s
                )
                select source,
                       count(*) as leads,
                       count(*) filter (where greatest(current_rank, ever_rank) >= 2) as reached_quotation,
                       count(*) filter (where status = 'BOOKING_CONFIRMED') as booked
                from ranked
                group by source
                order by count(*) desc, source
                """.formatted(where);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        List<SalesFunnelResponse.SourceBreakdown> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long leads = asLong(r.get("leads"));
            long booked = asLong(r.get("booked"));
            out.add(new SalesFunnelResponse.SourceBreakdown(
                    (String) r.get("source"), leads,
                    asLong(r.get("reached_quotation")), booked, pct(booked, leads)));
        }
        return out;
    }

    // ==================================================================
    // 2. Trip / Batch Performance
    // ==================================================================

    @Transactional(readOnly = true)
    public TripPerformanceResponse tripPerformance(ReportFilter requested, UserPrincipal caller) {
        ReportFilter f = scopeToCaller(requested, caller);

        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1=1");

        if (f.tripId() != null) {
            where.append(" and t.id = :tripId");
            p.addValue("tripId", f.tripId());
        }
        // The consultant filter reaches bookings through the commission ledger,
        // which is the same owner-at-confirmation attribution Team Performance
        // uses. Joining leads.owner_id here instead would produce a different
        // answer for reassigned leads, which is the exact inconsistency V13 exists
        // to prevent.
        if (f.consultantId() != null) {
            where.append("""
                     and exists (select 1 from sales_commission_ledger scl
                                 where scl.trip_id = t.id
                                   and scl.consultant_id = :consultantId
                                   and scl.revoked_at is null)
                    """);
            p.addValue("consultantId", f.consultantId());
        }
        appendTravelWindow(where, p, f, "b.travel_date");

        String sql = """
                select t.id                                   as trip_id,
                       t.name                                 as name,
                       t.duration_days                        as duration_days,
                       count(distinct ba.id)                  as batches,
                       count(b.id)                            as bookings,
                       coalesce(sum(b.num_travellers), 0)     as pax,
                       coalesce(sum(CASE WHEN b.status <> 'CANCELLED' THEN b.num_travellers ELSE 0 END), 0) as seats_booked,
                       coalesce(sum(CASE WHEN b.status <> 'CANCELLED' THEN ba.max_capacity ELSE 0 END), 0) as max_capacity,
                       coalesce(sum(CASE WHEN b.status IN ('CONFIRMED','COMPLETED')
                                         THEN b.total_amount - b.discount_amount + b.tax_amount
                                         ELSE 0 END), 0)   as revenue,
                       t.base_cost                            as base_cost
                from trips t
                  left join batches ba on ba.trip_id = t.id
                  left join bookings b on b.batch_id = ba.id
                %s
                group by t.id, t.name, t.duration_days, t.base_cost
                order by revenue desc
                """.formatted(where);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        List<TripPerformanceResponse.Trip> trips = new ArrayList<>();
        long totalBookings = 0;
        long totalPax = 0;
        BigDecimal totalRevenue = BigDecimal.ZERO;

        for (Map<String, Object> r : rows) {
            long batches = asLong(r.get("batches"));
            long bookings = asLong(r.get("bookings"));
            long pax = asLong(r.get("pax"));
            long seats = asLong(r.get("seats_booked"));
            long cap = asLong(r.get("max_capacity"));
            BigDecimal revenue = asDecimal(r.get("revenue"));
            Object baseCost = r.get("base_cost");
            BigDecimal revenuePerBooking = bookings == 0
                    ? null : revenue.divide(BigDecimal.valueOf(bookings), 2, RoundingMode.HALF_UP);

            trips.add(new TripPerformanceResponse.Trip(
                    (UUID) r.get("trip_id"),
                    (String) r.get("name"),
                    (int) asLong(r.get("duration_days")),
                    batches, bookings, pax, seats, cap,
                    cap == 0 ? null : pct(seats, cap),
                    revenue, revenuePerBooking,
                    // Null, not zero, when base_cost is unknown.
                    baseCost == null ? null : revenue.subtract(
                            ((BigDecimal) baseCost).multiply(BigDecimal.valueOf(pax)))));
            totalBookings += bookings;
            totalPax += pax;
            totalRevenue = totalRevenue.add(revenue);
        }

        TripPerformanceResponse.CostBasis basis = new TripPerformanceResponse.CostBasis(
                "trips.base_cost",
                true,
                true,
                "revenueLessBaseCost = revenue - (trips.base_cost * travellers). base_cost is "
                        + "treated as a per-person figure, which matches the seeded data but is not a "
                        + "documented business rule. It covers guide cost only: hotels, transport, "
                        + "permits, insurance, fees and overhead have no columns in this schema, so this "
                        + "is NOT a margin. True P&L is Phase 9.");

        return new TripPerformanceResponse(
                scopeLabel(caller), f, basis, trips,
                new TripPerformanceResponse.Totals(trips.size(), totalBookings, totalPax, totalRevenue));
    }

    // ==================================================================
    // 3. Team Performance
    // ==================================================================

    /**
     * Revenue is read from the ledger, so it is owner-at-confirmation and
     * unaffected by later reassignment. Revoked credits are excluded from the
     * revenue total and counted in {@code creditsRevoked} instead, so a
     * cancellation is visible rather than silently absent.
     */
    @Transactional(readOnly = true)
    public TeamPerformanceResponse teamPerformance(ReportFilter requested, UserPrincipal caller) {
        ReportFilter f = scopeToCaller(requested, caller);

        MapSqlParameterSource p = new MapSqlParameterSource();

        StringBuilder ledgerWhere = new StringBuilder(" where 1=1");
        if (f.consultantId() != null) {
            ledgerWhere.append(" and l.consultant_id = :consultantId");
            p.addValue("consultantId", f.consultantId());
        }
        if (f.tripId() != null) {
            ledgerWhere.append(" and l.trip_id = :tripId");
            p.addValue("tripId", f.tripId());
        }
        if (f.from() != null) {
            ledgerWhere.append(" and l.credited_at >= :fromTs");
            p.addValue("fromTs", Timestamp.from(f.from().atStartOfDay(ZONE).toInstant()));
        }
        if (f.to() != null) {
            // Half-open, so an adjacent period does not double count the boundary day.
            ledgerWhere.append(" and l.credited_at < :toTs");
            p.addValue("toTs", Timestamp.from(f.to().atStartOfDay(ZONE).toInstant()));
        }
        // The season filter narrows the credit window by the month of the travel
        // date, not the month it was credited, so a January booking credited in
        // December still lands in the season its trip belongs to.
        if (f.season() != null) {
            ledgerWhere.append("""
                     and exists (select 1 from bookings b
                                 where b.id = l.booking_id
                                   and extract(month from b.travel_date) in (:seasonMonths))
                    """);
            p.addValue("seasonMonths", f.season().monthList());
        }

        String sql = """
select u.id                              as consultant_id,
                         u.full_name                        as full_name,
                         u.email                            as email,
                         (select count(*) from leads ld
                            where ld.owner_id = u.id
                              %s)                           as leads_owned,
                         (select count(*) from leads ld
                            where ld.owner_id = u.id
                              and ld.status = 'BOOKING_CONFIRMED'
                              %s)                           as leads_booked,
                         (select count(*) from sales_commission_ledger cl
                           where cl.consultant_id = u.id
                             and cl.revoked_at is null
                             %s)                           as credits,
                         (select count(*) from sales_commission_ledger cl
                           where cl.consultant_id = u.id
                             and cl.revoked_at is not null
                             %s)                           as revoked,
                         (select coalesce(sum(cl.net_amount), 0)
                            from sales_commission_ledger cl
                           where cl.consultant_id = u.id
                             and cl.revoked_at is null
                             %s)                           as revenue
                  from users u
                  where u.is_active = true
                    -- Scoped callers (SALES self-scope and any explicit
                    -- consultantId filter) get exactly the requested row. The
                    -- exists(...) gates below are a PRESENCE test, not a scope
                    -- test: they decide which consultants are worth a row at all
                    -- (leads OR credits OR tasks), so without u.id here a SALES
                    -- user asking "how am I doing" would see every colleague's
                    -- revenue — the exact leak scopeToCaller exists to prevent.
                    %s
                    and (exists (select 1 from sales_commission_ledger l %s)
                         or exists (select 1 from leads ld where ld.owner_id = u.id)
                         -- A consultant who has never sold anything still has tasks and
                         -- therefore still has an SLA compliance rate. Without this,
                         -- their row disappears and the compliance they are being
                         -- measured on becomes invisible.
                         or exists (select 1 from tasks t where t.assignee_id = u.id))
                  order by revenue desc, u.full_name
                  """.formatted(
                  leadDatePredicate(f, p, "ld.created_at"),
                  leadDatePredicate(f, p, "ld.created_at"),
                  ledgerCreditsPredicate(f, p, "cl.credited_at"),
                  ledgerCreditsPredicate(f, p, "cl.credited_at"),
                  ledgerCreditsPredicate(f, p, "cl.credited_at"),
                  userScopeWhere(f, p),
                  ledgerWhere);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);

        // SLA compliance is measured per consultant over tasks that have an
        // sla_deadline at all. A task with no SLA is excluded rather than counted
        // as met, because including them would report a perfect compliance rate
        // for consultants whose tasks simply were never given a deadline.
        Map<UUID, long[]> sla = slaByConsultant(f, p);

        List<TeamPerformanceResponse.Consultant> out = new ArrayList<>();
        BigDecimal totalRevenue = BigDecimal.ZERO;
        long totalRevoked = 0;

        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("consultant_id");
            long leadsOwned = asLong(r.get("leads_owned"));
            long leadsBooked = asLong(r.get("leads_booked"));
            BigDecimal revenue = asDecimal(r.get("revenue"));
            long[] s = sla.getOrDefault(id, new long[]{0, 0});
            long revoked = asLong(r.get("revoked"));

            out.add(new TeamPerformanceResponse.Consultant(
                    id,
                    (String) r.get("full_name"),
                    (String) r.get("email"),
                    leadsOwned, leadsBooked, pct(leadsBooked, leadsOwned),
                    asLong(r.get("credits")), revoked, revenue,
                    s[0], s[1], pct(s[1], s[0])));

            totalRevenue = totalRevenue.add(revenue);
            totalRevoked += revoked;
        }

        TeamPerformanceResponse.SlaBasis slaBasis = new TeamPerformanceResponse.SlaBasis(
                true,
                "Compliance counts only tasks carrying an sla_deadline, and counts a task met when "
                        + "completed_at <= sla_deadline. Tasks with no deadline are excluded rather than "
                        + "counted as met, so an unconfigured SLA cannot inflate the rate.");

        return new TeamPerformanceResponse(
                scopeLabel(caller), f, slaBasis, out,
                new TeamPerformanceResponse.Totals(out.size(), totalRevenue, totalRevoked));
    }

    /**
     * {due, met} per consultant. The date window is deliberately NOT applied
     * here: sla_deadline is compared against completed_at, and restricting which
     * tasks are examined by the credit window would make compliance depend on
     * whether that consultant happened to sell anything in the period.
     */
    private Map<UUID, long[]> slaByConsultant(ReportFilter f, MapSqlParameterSource baseParams) {
        MapSqlParameterSource p = new MapSqlParameterSource(baseParams.getValues());
        StringBuilder w = new StringBuilder(" where t.sla_deadline is not null");
        if (f.consultantId() != null) {
            w.append(" and t.assignee_id = :consultantId");
        }

        String sql = """
                select t.assignee_id as consultant_id,
                       count(*) as due,
                       count(*) filter (where t.completed_at is not null
                                          and t.completed_at <= t.sla_deadline) as met
                from tasks t %s
                group by t.assignee_id
                """.formatted(w);

        Map<UUID, long[]> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, p)) {
            out.put((UUID) r.get("consultant_id"),
                    new long[]{asLong(r.get("due")), asLong(r.get("met"))});
        }
        return out;
    }

    // ==================================================================
    // 4. Operations Readiness
    // ==================================================================

    @Transactional(readOnly = true)
    public OperationsReadinessResponse operationsReadiness(ReportFilter requested, UserPrincipal caller) {
        ReportFilter f = scopeToCaller(requested, caller);

        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1=1");
        if (f.tripId() != null) {
            where.append(" and t.id = :tripId");
            p.addValue("tripId", f.tripId());
        }
        appendTravelWindow(where, p, f, "h.travel_date");

        String sql = """
                select ba.id                                as batch_id,
                       h.id                                 as handoff_id,
                       t.id            as trip_id,
                       t.name          as trip_name,
                       h.ops_ref       as batch_ref,
                       h.travel_date   as departure_date,
                       h.pax           as pax,
                       coalesce(ba.max_capacity, 0)  as max_capacity,
                       coalesce(ba.seats_booked, 0) as seats_booked,
                       h.hotel_status     as hotel_status,
                       h.transport_status as transport_status,
                       h.payment_status   as payment_status,
                       (h.trip_sheet_generated_at is not null) as trip_sheet
                from operations_handoffs h
                  join batches ba on ba.id = h.batch_id
                  join trips t on t.id = ba.trip_id
                %s
                order by h.travel_date nulls last, h.ops_ref
                """.formatted(where);

        List<OperationsReadinessResponse.BatchReadiness> batches = new ArrayList<>();
        long withGaps = 0;
        for (Map<String, Object> r : jdbc.queryForList(sql, p)) {
            long seats = asLong(r.get("seats_booked"));
            long cap = asLong(r.get("max_capacity"));
            boolean sheet = Boolean.TRUE.equals(r.get("trip_sheet"));

            List<String> gaps = new ArrayList<>();
            if (!"CONFIRMED".equals(r.get("hotel_status"))) {
                gaps.add("hotel not confirmed: " + r.get("hotel_status"));
            }
            if (!"CONFIRMED".equals(r.get("transport_status"))) {
                gaps.add("transport not confirmed: " + r.get("transport_status"));
            }
            if (!"CONFIRMED".equals(r.get("payment_status"))) {
                gaps.add("payment not confirmed: " + r.get("payment_status"));
            }
            if (!sheet) {
                gaps.add("trip sheet not generated");
            }
            if (cap == 0) {
                gaps.add("batch declares no capacity");
            } else if (seats > cap) {
                gaps.add("overbooked: " + seats + " seats against capacity " + cap);
            }
            if (!gaps.isEmpty()) {
                withGaps++;
            }

            batches.add(new OperationsReadinessResponse.BatchReadiness(
                    (UUID) r.get("batch_id"), (UUID) r.get("trip_id"),
                    (String) r.get("trip_name"), (String) r.get("batch_ref"),
                    r.get("departure_date"),
                    asLong(r.get("pax")), seats, cap,
                    cap == 0 ? null : pct(seats, cap),
                    (String) r.get("hotel_status"), (String) r.get("transport_status"),
                    (String) r.get("payment_status"), sheet, gaps));
        }

        List<OperationsReadinessResponse.VendorScorecard> vendors = vendorScorecards();

        // Explicitly unavailable, never zero. See the DTO note: reporting 0
        // incidents would assert that nothing has ever gone wrong, backed by no
        // data at all.
        OperationsReadinessResponse.IncidentSummary incidents =
                new OperationsReadinessResponse.IncidentSummary(
                        false,
                        "Trip incident logging is not built yet, so no incident data exists to summarise. "
                                + "This is NOT a report of zero incidents.",
                        "Phase 3 Module 1 (mobile trip log)",
                        null, null);

        long handoffs = vendors.stream().mapToLong(
                OperationsReadinessResponse.VendorScorecard::handoffsAssigned).sum();

        return new OperationsReadinessResponse(
                scopeLabel(caller), f, incidents, batches, vendors,
                new OperationsReadinessResponse.Totals(
                        batches.size(), withGaps, vendors.size(), handoffs));
    }

    /**
     * Completion rates per vendor, from handoff assignment and confirmation.
     *
     * <p>A vendor can occupy four distinct slots on a handoff, so the four
     * relationships are unioned and de-duplicated by (handoff, vendor) to avoid
     * counting a single handoff twice when one vendor fills two roles.
     */
    private List<OperationsReadinessResponse.VendorScorecard> vendorScorecards() {
        MapSqlParameterSource p = new MapSqlParameterSource();

        String sql = """
                with assignments as (
                    select id as handoff_id, hotel_vendor_id     as vendor_id, 'hotel'     as role, hotel_status     as status from operations_handoffs where hotel_vendor_id is not null
                    union all
                    select id, transport_vendor_id, 'transport', transport_status from operations_handoffs where transport_vendor_id is not null
                    union all
                    select id, guide_id,           'guide',     null                from operations_handoffs where guide_id is not null
                    union all
                    select id, driver_id,          'driver',    null                from operations_handoffs where driver_id is not null
                )
                select v.id as vendor_id,
                       v.name as vendor_name,
                       v.category as category,
                       count(*) as assigned,
                       count(*) filter (where a.status = 'CONFIRMED') as confirmed
                from assignments a
                  join vendors v on v.id = a.vendor_id
                group by v.id, v.name, v.category
                order by v.name
                """;

        List<OperationsReadinessResponse.VendorScorecard> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, p)) {
            long assigned = asLong(r.get("assigned"));
            long confirmed = asLong(r.get("confirmed"));
            out.add(new OperationsReadinessResponse.VendorScorecard(
                    (UUID) r.get("vendor_id"), (String) r.get("vendor_name"),
                    (String) r.get("category"),
                    "handoff assignment and confirmation status only",
                    assigned, confirmed, pct(confirmed, assigned),
                    false));
        }
        return out;
    }

    // ==================================================================
    // 5. Customer Insights
    // ==================================================================

    /**
     * Lifetime value is summed live from bookings.
     *
     * <p>{@code customer360.total_spent} is not read: on the seeded database it
     * reports 0.00 for a customer with 175,000 of net bookings, so a report built
     * on it would state that every customer is worth nothing.
     */
    @Transactional(readOnly = true)
    public CustomerInsightsResponse customerInsights(ReportFilter requested, UserPrincipal caller) {
        ReportFilter f = scopeToCaller(requested, caller);

        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1=1");
        if (f.consultantId() != null) {
            where.append("""
                     and exists (select 1 from bookings b2
                                  join sales_commission_ledger scl on scl.booking_id = b2.id
                                 where b2.customer_id = c.id
                                   and scl.consultant_id = :consultantId
                                   and scl.revoked_at is null)
                    """);
            p.addValue("consultantId", f.consultantId());
        }
        if (f.tripId() != null) {
            where.append(" and exists (select 1 from bookings b3 where b3.customer_id = c.id and b3.trip_id = :tripId)");
            p.addValue("tripId", f.tripId());
        }
        appendTravelWindow(where, p, f, "b.travel_date");

        String sql = """
                select c.id as customer_id,
                       c.full_name as full_name,
                       c.email as email,
                       coalesce(c.mobile_number, c.whatsapp_number) as mobile_number,
                       count(b.id) filter (where b.status in ('CONFIRMED','COMPLETED')) as bookings,
                       max(extract(year from b.travel_date))
                           filter (where b.status in ('CONFIRMED','COMPLETED')) as last_year,
                       coalesce(sum(b.total_amount - b.discount_amount + b.tax_amount)
                           filter (where b.status in ('CONFIRMED','COMPLETED')), 0) as ltv
                from customer360 c
                  left join bookings b on b.customer_id = c.id
                %s
                group by c.id, c.full_name, c.email, c.mobile_number, c.whatsapp_number
                having count(b.id) > 0
                order by ltv desc
                """.formatted(where);

        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        List<CustomerInsightsResponse.Customer> out = new ArrayList<>();
        long repeat = 0;
        long atRisk = 0;
        BigDecimal totalLtv = BigDecimal.ZERO;

        int currentYear = LocalDate.now(ZONE).getYear();
        int staleYears = 2;

        for (Map<String, Object> r : rows) {
            long bookings = asLong(r.get("bookings"));
            BigDecimal ltv = asDecimal(r.get("ltv"));
            Object lastYear = r.get("last_year");
            int year = lastYear == null ? 0 : (int) asLong(lastYear);

            boolean isRepeat = bookings > 1;
            // Risk heuristic, not a prediction: a customer with history whose most
            // recent trip predates the window. The field is called churnReason
            // rather than a score, because the inputs for a real model do not
            // exist in this schema.
            boolean risk = bookings > 0 && year > 0 && (currentYear - year) >= staleYears;
            String reason = risk
                    ? "last booking in " + year + ", at least " + staleYears + " years ago"
                    : null;

            if (isRepeat) repeat++;
            if (risk) atRisk++;
            totalLtv = totalLtv.add(ltv);

            out.add(new CustomerInsightsResponse.Customer(
                    (UUID) r.get("customer_id"), (String) r.get("full_name"),
                    (String) r.get("email"), (String) r.get("mobile_number"),
                    bookings, year, ltv, isRepeat, risk, reason));
        }

        List<String> topDestinations = topDestinations();

        CustomerInsightsResponse.ValueBasis basis = new CustomerInsightsResponse.ValueBasis(
                "bookings (live sum of total_amount - discount_amount + tax_amount)",
                true,
                "customer360.total_spent is deliberately NOT used: it is maintained by "
                        + "Customer360Service.maintainAggregates and reads 0.00 on the seeded database "
                        + "while actual net bookings are 175,000.");

        return new CustomerInsightsResponse(
                scopeLabel(caller), f, basis,
                new CustomerInsightsResponse.Totals(
                        out.size(), repeat, pct(repeat, out.size()), totalLtv,
                        out.isEmpty() ? BigDecimal.ZERO
                                : totalLtv.divide(BigDecimal.valueOf(out.size()), 2, RoundingMode.HALF_UP),
                        atRisk),
                topDestinations, out);
    }

    private List<String> topDestinations() {
        String sql = """
                select l.destination, count(*) as n
                from leads l
                where l.destination is not null
                  and l.status = 'BOOKING_CONFIRMED'
                group by l.destination
                order by count(*) desc, l.destination
                limit 10
                """;
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, new MapSqlParameterSource())) {
            out.add((String) r.get("destination"));
        }
        return out;
    }

    // ==================================================================
    // 6. Audit search
    // ==================================================================

    /**
     * Paginated audit search combining full-text rank with trigram similarity.
     *
     * <p>Full text alone cannot find "Ramesh" when the row says "Ramesh.New@x.com"
     * is handled inside the generated tsvector, but a typo like "Ramash" still
     * matches nothing because tsvector has no notion of near-misses. So a query
     * that finds no full-text hits is retried with trigram similarity, and each
     * hit reports which strategy found it.
     *
     * <p>{@code query} is validated to a sane length and never concatenated into
     * SQL; it is always a bound parameter.
     */
    @Transactional(readOnly = true)
    public AuditSearchResponse searchAudit(String query,
                                           String entity,
                                           UUID actorId,
                                           LocalDate from,
                                           LocalDate to,
                                           int page,
                                           int size,
                                           UserPrincipal caller) {
        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        String q = query == null || query.isBlank() ? null : query.trim();
        if (q != null && q.length() > 200) {
            q = q.substring(0, 200);
        }

        MapSqlParameterSource p = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" where 1=1");
        if (q != null) {
            where.append(" and (a.search_vector @@ websearch_to_tsquery('english', :q)"
                    + " or coalesce(a.search_vector @@ plainto_tsquery('simple', :q), false)"
                    + " or similarity(coalesce(a.new_value, ''), :q) > 0.3"
                    + " or similarity(coalesce(a.old_value, ''), :q) > 0.3)");
            p.addValue("q", q);
        }
        if (entity != null && !entity.isBlank()) {
            where.append(" and a.entity = :entity");
            p.addValue("entity", entity.trim());
        }
        if (actorId != null) {
            where.append(" and a.actor_id = :actorId");
            p.addValue("actorId", actorId);
        }
        if (from != null) {
            where.append(" and a.created_at >= :fromTs");
            p.addValue("fromTs", Timestamp.from(from.atStartOfDay(ZONE).toInstant()));
        }
        if (to != null) {
            where.append(" and a.created_at < :toTs");
            p.addValue("toTs", Timestamp.from(to.atStartOfDay(ZONE).toInstant()));
        }

        long offset = (long) safePage * safeSize;
        p.addValue("limit", safeSize);
        p.addValue("offset", offset);

        String sql = """
                select a.id, a.entity, a.entity_id, a.action, a.field,
                       a.old_value, a.new_value, a.actor_id, a.created_at, a.seq,
                       u.full_name as actor_name,
                       case when a.search_vector @@ websearch_to_tsquery('english', :q)
                            then ts_rank(a.search_vector, websearch_to_tsquery('english', :q))
                            else null end as fts_rank,
                       greatest(coalesce(similarity(coalesce(a.new_value, ''), :q), 0),
                                coalesce(similarity(coalesce(a.old_value, ''), :q), 0)) as trigram
                from audit_log a
                  left join users u on u.id = a.actor_id
                %s
                order by (a.search_vector @@ websearch_to_tsquery('english', :q)) desc nulls last,
                         a.created_at desc, a.seq desc
                limit :limit offset :offset
                """.formatted(where);

        // websearch_to_tsquery is referenced in ORDER BY even when there is no
        // text query; it needs a bound value or it throws on an untyped NULL.
        p.addValue("q", q == null ? "" : q);

        List<AuditSearchResponse.Hit> hits = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, p)) {
            Double rank = asDouble(r.get("fts_rank"));
            Double trigram = asDouble(r.get("trigram"));
            String matchedBy;
            if (q == null) {
                matchedBy = "none";
            } else if (rank != null && rank > 0) {
                matchedBy = "fts";
            } else if (trigram != null && trigram > 0.3) {
                matchedBy = "trigram";
            } else {
                matchedBy = "none";
            }
            hits.add(new AuditSearchResponse.Hit(
                    (UUID) r.get("id"), (String) r.get("entity"), (UUID) r.get("entity_id"),
                    (String) r.get("action"), (String) r.get("field"),
                    (String) r.get("old_value"), (String) r.get("new_value"),
                    (UUID) r.get("actor_id"), (String) r.get("actor_name"),
                    r.get("created_at") == null ? null : ((Timestamp) r.get("created_at")).toInstant(),
                    r.get("seq") == null ? null : asLong(r.get("seq")),
                    matchedBy, rank, trigram));
        }

        AuditSearchResponse.SearchMode mode = q == null
                ? AuditSearchResponse.SearchMode.STRUCTURED
                : AuditSearchResponse.SearchMode.TEXT;

        return new AuditSearchResponse(q, mode, hits.size(), safePage, safeSize, hits);
    }

    // ==================================================================
    // Filter SQL helpers
    //
    // Every one of these appends bound parameters only. No caller-supplied text
    // is ever concatenated into a statement.
    // ==================================================================

    private String leadWhere(ReportFilter f, String alias, MapSqlParameterSource p) {
        StringBuilder w = new StringBuilder(" where 1=1");
        if (f.consultantId() != null) {
            w.append(" and ").append(alias).append(".owner_id = :consultantId");
            p.addValue("consultantId", f.consultantId());
        }
        if (f.source() != null && !f.source().isBlank()) {
            w.append(" and ").append(alias).append(".source = :source");
            p.addValue("source", f.source().trim());
        }
        if (f.from() != null) {
            w.append(" and ").append(alias).append(".created_at >= :leadFromTs");
            p.addValue("leadFromTs", Timestamp.from(f.from().atStartOfDay(ZONE).toInstant()));
        }
        if (f.to() != null) {
            w.append(" and ").append(alias).append(".created_at < :leadToTs");
            p.addValue("leadToTs", Timestamp.from(f.to().atStartOfDay(ZONE).toInstant()));
        }
        if (f.season() != null) {
            w.append(" and extract(month from ").append(alias)
                    .append(".created_at) in (:leadSeasonMonths)");
            p.addValue("leadSeasonMonths", f.season().monthList());
        }
        return w.toString();
    }

    /** Date/season window on a travel date column. */
    private void appendTravelWindow(StringBuilder w, MapSqlParameterSource p,
                                    ReportFilter f, String dateColumn) {
        if (f.from() != null) {
            w.append(" and ").append(dateColumn).append(" >= :travelFrom");
            p.addValue("travelFrom", f.from());
        }
        if (f.to() != null) {
            w.append(" and ").append(dateColumn).append(" < :travelTo");
            p.addValue("travelTo", f.to());
        }
        if (f.season() != null) {
            w.append(" and extract(month from ").append(dateColumn).append(") in (:seasonMonths)");
            p.addValue("seasonMonths", f.season().monthList());
        }
    }

    /** The credit-window conditions, repeated in each subquery of teamPerformance. */
    private String ledgerCreditsPredicate(ReportFilter f, MapSqlParameterSource p, String column) {
        StringBuilder w = new StringBuilder();
        if (f.from() != null) {
            w.append(" and ").append(column).append(" >= :credFromTs");
            p.addValue("credFromTs", Timestamp.from(f.from().atStartOfDay(ZONE).toInstant()));
        }
        if (f.to() != null) {
            w.append(" and ").append(column).append(" < :credToTs");
            p.addValue("credToTs", Timestamp.from(f.to().atStartOfDay(ZONE).toInstant()));
        }
        return w.toString();
    }

/** The lead-date window, repeated in each subquery of teamPerformance. */
      private String leadDatePredicate(ReportFilter f, MapSqlParameterSource p, String column) {
          StringBuilder w = new StringBuilder();
          if (f.from() != null) {
              w.append(" and ").append(column).append(" >= :ldFromTs");
              p.addValue("ldFromTs", Timestamp.from(f.from().atStartOfDay(ZONE).toInstant()));
          }
          if (f.to() != null) {
              w.append(" and ").append(column).append(" < :ldToTs");
              p.addValue("ldToTs", Timestamp.from(f.to().atStartOfDay(ZONE).toInstant()));
          }
          return w.toString();
      }

      /**
       * Narrows the team row set to the requested consultant.
       *
       * <p>Without this the presence gates (exists leads / credits / tasks) decide
       * the row set, so a consultant-filtered query still returned every colleague
       * who owns anything. The bound {@code :consultantId} already exists from
       * {@code ledgerWhere}.
       */
      private String userScopeWhere(ReportFilter f, MapSqlParameterSource p) {
          if (f.consultantId() == null) {
              return "";
          }
          return "and u.id = :consultantId";
      }

    // ==================================================================
    // Small helpers
    // ==================================================================

    private Map<String, Object> single(String sql, MapSqlParameterSource p) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, p);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static long asLong(Object o) {
        if (o == null) return 0L;
        if (o instanceof Number n) return n.longValue();
        return Long.parseLong(o.toString());
    }

    private static BigDecimal asDecimal(Object o) {
        if (o == null) return BigDecimal.ZERO;
        if (o instanceof BigDecimal b) return b;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(o.toString());
    }

    /**
     * Nullable numeric read that tolerates the concrete JDBC type.
     *
     * <p>Not a {@code (Double)} cast: Postgres {@code ts_rank} and
     * {@code pg_trgm} similarity both return {@code real} (float4), which the
     * driver hands back as a {@link Float}. Casting straight to Double throws
     * ClassCastException and takes down the whole audit search, which is exactly
     * the endpoint where a null-tolerant read matters most.
     */
    private static Double asDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        return Double.valueOf(o.toString());
    }

    /**
     * Percentage to one decimal, or null when there is no denominator.
     *
     * <p>Null rather than 0.0 matters: "0% of zero leads booked" and "no leads in
     * scope" are different facts, and collapsing them would make an empty report
     * look like a failing one.
     */
    private static BigDecimal pct(long part, long whole) {
        if (whole == 0) return null;
        return BigDecimal.valueOf(part)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
