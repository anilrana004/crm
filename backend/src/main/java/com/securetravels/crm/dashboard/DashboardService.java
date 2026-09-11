package com.securetravels.crm.dashboard;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.dashboard.dto.DashboardSummaryResponse;
import com.securetravels.crm.dashboard.dto.PerformanceResponse;
import com.securetravels.crm.dashboard.dto.TargetDto;
import com.securetravels.crm.dashboard.dto.TargetListResponse;
import com.securetravels.crm.dashboard.dto.TargetProgressResponse;
import com.securetravels.crm.dashboard.dto.TargetUpsertRequest;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Module 8 (legacy M4/M5/M6): aggregate sales dashboard, per-employee
 * performance and monthly sales-target tracking. Aggregates read alongside
 * the booking/payment pipeline; targets are config rows in sales_targets.
 */
@Service
public class DashboardService {

    private static final String[] BOOKED = {"CONFIRMED", "COMPLETED"};
    private static final String[] APPLIED = {"COMPLETED", "PARTIAL"};
    private static final String COMPANY_LABEL = "Company (all sales)";

    private final JdbcTemplate jdbc;
    private final SalesTargetRepository targetRepo;
    private final UserRepository userRepo;
    private final AuditService auditService;

    public DashboardService(JdbcTemplate jdbc,
                            SalesTargetRepository targetRepo,
                            UserRepository userRepo,
                            AuditService auditService) {
        this.jdbc = jdbc;
        this.targetRepo = targetRepo;
        this.userRepo = userRepo;
        this.auditService = auditService;
    }

    private static ZoneId zone() { return ZoneId.systemDefault(); }

    /** YYYY-MM (or yyyyMM) -> first day of that month, defaulting to current month. */
    public static LocalDate monthStart(String month) {
        YearMonth ym;
        if (month == null || month.isBlank()) {
            ym = YearMonth.now();
        } else {
            try {
                ym = YearMonth.parse(month);
            } catch (DateTimeParseException e) {
                throw new BadRequestException("month must be YYYY-MM, got: " + month);
            }
        }
        return ym.atDay(1);
    }

    // ------------------------------------------------------------------
    // M4 — dashboard cards
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary(String period, UserPrincipal caller) {
        boolean today = period == null || period.isBlank() || "today".equalsIgnoreCase(period);
        LocalDate anchor = today ? LocalDate.now(zone()) : LocalDate.now(zone()).withDayOfMonth(1);
        LocalDate asOf = LocalDate.now(zone());
        java.sql.Timestamp from = java.sql.Timestamp.from(anchor.atStartOfDay(zone()).toInstant());

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT
                  (SELECT count(*) FROM leads)                                            AS total_leads,
                  (SELECT count(*) FROM leads WHERE status = 'NEW'
                       AND created_at >= CAST(? AS timestamptz))                          AS new_leads,
                  (SELECT count(*) FROM leads
                       WHERE status NOT IN ('LOST','BOOKING_CONFIRMED')
                         AND follow_up_date IS NOT NULL AND follow_up_date <= ?)          AS follow_up_due,
                  (SELECT count(*) FROM leads WHERE status = 'INTERESTED')                AS interested,
                  (SELECT count(*) FROM leads WHERE status = 'QUOTATION_SENT')            AS quotation_sent,
                  (SELECT count(*) FROM leads WHERE status = 'BOOKING_CONFIRMED')          AS booking_confirmed,
                  (SELECT count(*) FROM leads WHERE status = 'LOST')                      AS lost,
                  (SELECT count(*) FROM leads WHERE status = 'BOOKING_CONFIRMED'
                       AND created_at >= CAST(? AS timestamptz))                          AS new_bookings,
                  (SELECT count(*) FROM bookings WHERE status IN ('CONFIRMED','COMPLETED')) AS confirmed_bookings,
                  (SELECT COALESCE(sum(amount), 0) FROM payments
                       WHERE status IN ('COMPLETED','PARTIAL'))                           AS revenue
                """, from, asOf, from);

        return new DashboardSummaryResponse(
                today ? "today" : "month",
                anchor,
                l(row.get("total_leads")),
                l(row.get("new_leads")),
                l(row.get("follow_up_due")),
                l(row.get("interested")),
                l(row.get("quotation_sent")),
                l(row.get("booking_confirmed")),
                l(row.get("lost")),
                l(row.get("new_bookings")),
                l(row.get("confirmed_bookings")),
                bd(row.get("revenue")));
    }

    // ------------------------------------------------------------------
    // M5 — per-employee performance
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PerformanceResponse performance(String month, UserPrincipal caller) {
        LocalDate start = monthStart(month);
        java.sql.Timestamp from = java.sql.Timestamp.from(start.atStartOfDay(zone()).toInstant());
        java.sql.Timestamp to = java.sql.Timestamp.from(start.plusMonths(1).atStartOfDay(zone()).toInstant());
        UUID scope = caller.role() == Role.SALES ? caller.id() : null;

        String base = """
                SELECT u.id                         AS user_id,
                       u.full_name                   AS full_name,
                       u.email                       AS email,
                       (SELECT count(*) FROM leads l
                            WHERE l.owner_id = u.id AND l.status IN ('NEW','INTERESTED','QUOTATION_SENT','BOOKING_CONFIRMED')
                              AND l.created_at >= CAST(? AS timestamptz)
                              AND l.created_at <  CAST(? AS timestamptz))              AS leads_assigned,
                       (SELECT count(*) FROM tasks t
                            WHERE t.assignee_id = u.id AND t.status = 'COMPLETED'
                              AND t.completed_at >= CAST(? AS timestamptz)
                              AND t.completed_at <  CAST(? AS timestamptz))            AS follow_ups_completed,
                       (SELECT count(*) FROM bookings b
                            WHERE b.created_by = u.id AND b.status IN ('CONFIRMED','COMPLETED')
                              AND b.created_at >= CAST(? AS timestamptz)
                              AND b.created_at <  CAST(? AS timestamptz))              AS bookings_closed,
                       (SELECT count(*) FROM payments p JOIN bookings pb ON pb.id = p.booking_id
                            WHERE pb.created_by = u.id AND p.status IN ('COMPLETED','PARTIAL')
                              AND COALESCE(p.paid_at, p.created_at) >= CAST(? AS timestamptz)
                              AND COALESCE(p.paid_at, p.created_at) <  CAST(? AS timestamptz)) AS payments_count,
                       (SELECT COALESCE(sum(p.amount), 0) FROM payments p JOIN bookings pb ON pb.id = p.booking_id
                            WHERE pb.created_by = u.id AND p.status IN ('COMPLETED','PARTIAL')
                              AND COALESCE(p.paid_at, p.created_at) >= CAST(? AS timestamptz)
                              AND COALESCE(p.paid_at, p.created_at) <  CAST(? AS timestamptz)) AS revenue
                  FROM users u
                 WHERE u.role = 'SALES' AND u.is_active = TRUE
                """;
        List<PerformanceResponse.Employee> employees = new ArrayList<>();
        if (scope == null) {
            Map<String, Object>[] maps = jdbc.queryForList(base + " ORDER BY u.full_name",
                    from, to, from, to, from, to, from, to, from, to).toArray(new Map[0]);
            Arrays.stream(maps).forEach(m -> employees.add(toEmployee(m)));
        } else {
            employees.add(toEmployee(jdbc.queryForMap(base + " AND u.id = ?", from, to, from, to,
                    from, to, from, to, from, to, scope)));
        }

        long leads = employees.stream().mapToLong(PerformanceResponse.Employee::leadsAssigned).sum();
        long followUps = employees.stream().mapToLong(PerformanceResponse.Employee::followUpsCompleted).sum();
        long closed = employees.stream().mapToLong(PerformanceResponse.Employee::bookingsClosed).sum();
        BigDecimal revenue = employees.stream()
                .map(PerformanceResponse.Employee::revenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PerformanceResponse(start.toString(), employees,
                new PerformanceResponse.Totals(leads, followUps, closed, revenue));
    }

    private static PerformanceResponse.Employee toEmployee(Map<String, Object> m) {
        return new PerformanceResponse.Employee(
                (UUID) m.get("user_id"),
                (String) m.get("full_name"),
                (String) m.get("email"),
                l(m.get("leads_assigned")),
                l(m.get("follow_ups_completed")),
                l(m.get("bookings_closed")),
                l(m.get("payments_count")),
                bd(m.get("revenue")));
    }

    // ------------------------------------------------------------------
    // M6 — monthly targets
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public TargetListResponse targets(String month, UserPrincipal caller) {
        return targetsFor(monthStart(month));
    }

    private TargetListResponse targetsFor(LocalDate start) {
        Map<UUID, long[]> achieved = bookingsByUser(start);
        Map<UUID, BigDecimal> revenue = revenueByUser(start);
        Map<UUID, String> names = names();

        List<TargetDto> targets = targetRepo.findByMonthOrderByUserIdAsc(start).stream()
                .map(t -> toDto(t, achieved.getOrDefault(t.getUserId(), new long[]{0L, 0L}),
                        revenue.getOrDefault(t.getUserId(), BigDecimal.ZERO), names))
                .toList();

        TargetListResponse.Overall overall = overall(start, targets);
        return new TargetListResponse(start, targets, overall);
    }

    @Transactional(readOnly = true)
    public TargetProgressResponse progress(String month, UserPrincipal caller) {
        LocalDate start = monthStart(month);
        TargetListResponse list = targetsFor(start);
        List<TargetDto> employees = list.targets().stream()
                .filter(t -> t.userId() != null)
                .toList();
        return new TargetProgressResponse(start, list.overall(), employees);
    }

    @Transactional
    public TargetDto upsertTarget(TargetUpsertRequest req, UserPrincipal caller) {
        LocalDate start = monthStart(req.month());
        int bookings = req.targetBookings() == null ? 0 : req.targetBookings();
        BigDecimal revenue = req.targetRevenue() == null ? BigDecimal.ZERO : req.targetRevenue();
        if (bookings == 0 && (revenue == null || revenue.signum() == 0)) {
            throw new BadRequestException("set targetBookings or targetRevenue");
        }
        Optional<SalesTarget> existing = targetRepo.findByUserIdAndMonth(req.userId(), start);
        SalesTarget t = existing.orElseGet(() ->
                new SalesTarget(req.userId(), start, 0, BigDecimal.ZERO, caller.id()));
        boolean created = !existing.isPresent();
        int oldBookings = t.getTargetBookings();
        BigDecimal oldRevenue = t.getTargetRevenue();
        t.setTargetBookings(bookings);
        t.setTargetRevenue(revenue);
        targetRepo.saveAndFlush(t);

        if (created) {
            auditService.record("SALES_TARGET", t.getId(), AuditAction.CREATE, "target",
                    null, t.getTargetBookings() + "/" + t.getTargetRevenue() + " month=" + start);
        } else {
            if (oldBookings != bookings) {
                auditService.record("SALES_TARGET", t.getId(), AuditAction.UPDATE, "target_bookings",
                        Integer.toString(oldBookings), Integer.toString(bookings));
            }
            if (oldRevenue.compareTo(revenue) != 0) {
                auditService.record("SALES_TARGET", t.getId(), AuditAction.UPDATE, "target_revenue",
                        oldRevenue.toPlainString(), revenue.toPlainString());
            }
        }

        Map<UUID, long[]> achieved = bookingsByUser(start);
        Map<UUID, BigDecimal> rev = revenueByUser(start);
        TargetDto dto = toDto(t, achieved.getOrDefault(t.getUserId(), new long[]{0L, 0L}),
                rev.getOrDefault(t.getUserId(), BigDecimal.ZERO), names());
        return dto;
    }

    @Transactional
    public void deleteTarget(UUID id) {
        if (!targetRepo.existsById(id)) {
            throw new NotFoundException("target not found: " + id);
        }
        auditService.record("SALES_TARGET", id, AuditAction.DELETE, "target",
                "bookings/revenue", null);
        targetRepo.deleteById(id);
    }

    private TargetListResponse.Overall overall(LocalDate start, List<TargetDto> targets) {
        Optional<TargetDto> company = targets.stream().filter(t -> t.userId() == null).findFirst();
        List<TargetDto> perUser = targets.stream().filter(t -> t.userId() != null).toList();
        int targetBookings = company.map(TargetDto::targetBookings)
                .orElse(perUser.stream().mapToInt(TargetDto::targetBookings).sum());
        BigDecimal targetRevenue = company.map(TargetDto::targetRevenue)
                .orElse(perUser.stream().map(TargetDto::targetRevenue).reduce(BigDecimal.ZERO, BigDecimal::add));

        long[] all = countsInWindow(start);
        long achievedBookings = targets.isEmpty() ? 0 : all[0];
        BigDecimal achievedRevenue = targets.isEmpty() ? BigDecimal.ZERO : revInWindow(start);
        return new TargetListResponse.Overall(
                targetBookings, targetRevenue,
                achievedBookings, achievedRevenue,
                pct(achievedBookings, targetBookings),
                pct(achievedRevenue, targetRevenue));
    }

    private TargetDto toDto(SalesTarget t, long[] achieved, BigDecimal rev, Map<UUID, String> names) {
        String label = t.getUserId() == null ? COMPANY_LABEL : names.getOrDefault(t.getUserId(), "Sales");
        return new TargetDto(t.getId(), t.getUserId(), label, t.getMonth(),
                t.getTargetBookings(), t.getTargetRevenue(),
                achieved[0], rev,
                pct(achieved[0], t.getTargetBookings()),
                pct(rev, t.getTargetRevenue()));
    }

    private Map<UUID, String> names() {
        return userRepo.findAll().stream()
                .collect(Collectors.toMap(User::getId, User::getFullName, (a, b) -> a));
    }

    private Map<UUID, long[]> bookingsByUser(LocalDate start) {
        java.sql.Timestamp from = java.sql.Timestamp.from(start.atStartOfDay(zone()).toInstant());
        java.sql.Timestamp to = java.sql.Timestamp.from(start.plusMonths(1).atStartOfDay(zone()).toInstant());
        Map<UUID, long[]> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT b.created_by AS user_id, count(*) AS cnt
                  FROM bookings b
                 WHERE b.status IN ('CONFIRMED','COMPLETED')
                   AND b.created_at >= CAST(? AS timestamptz)
                   AND b.created_at <  CAST(? AS timestamptz)
                 GROUP BY b.created_by
                """, from, to).forEach(row -> out.put((UUID) row.get("user_id"), new long[]{0L, l(row.get("cnt"))}));
        return out;
    }

    private Map<UUID, BigDecimal> revenueByUser(LocalDate start) {
        java.sql.Timestamp from = java.sql.Timestamp.from(start.atStartOfDay(zone()).toInstant());
        java.sql.Timestamp to = java.sql.Timestamp.from(start.plusMonths(1).atStartOfDay(zone()).toInstant());
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        jdbc.queryForList("""
                SELECT b.created_by AS user_id, COALESCE(sum(p.amount), 0) AS rev
                  FROM payments p JOIN bookings b ON b.id = p.booking_id
                 WHERE p.status IN ('COMPLETED','PARTIAL')
                   AND COALESCE(p.paid_at, p.created_at) >= CAST(? AS timestamptz)
                   AND COALESCE(p.paid_at, p.created_at) <  CAST(? AS timestamptz)
                 GROUP BY b.created_by
                """, from, to).forEach(row -> out.put((UUID) row.get("user_id"), bd(row.get("rev"))));
        return out;
    }

    private long[] countsInWindow(LocalDate start) {
        java.sql.Timestamp from = java.sql.Timestamp.from(start.atStartOfDay(zone()).toInstant());
        java.sql.Timestamp to = java.sql.Timestamp.from(start.plusMonths(1).atStartOfDay(zone()).toInstant());
        return jdbc.queryForList("""
                SELECT count(*) AS cnt FROM bookings
                 WHERE status IN ('CONFIRMED','COMPLETED')
                   AND created_at >= CAST(? AS timestamptz)
                   AND created_at <  CAST(? AS timestamptz)
                """, from, to).stream().mapToLong(r -> l(r.get("cnt"))).toArray();
    }

    private BigDecimal revInWindow(LocalDate start) {
        java.sql.Timestamp from = java.sql.Timestamp.from(start.atStartOfDay(zone()).toInstant());
        java.sql.Timestamp to = java.sql.Timestamp.from(start.plusMonths(1).atStartOfDay(zone()).toInstant());
        return jdbc.queryForObject("""
                SELECT COALESCE(sum(p.amount), 0) FROM payments p JOIN bookings b ON b.id = p.booking_id
                 WHERE p.status IN ('COMPLETED','PARTIAL')
                   AND COALESCE(p.paid_at, p.created_at) >= CAST(? AS timestamptz)
                   AND COALESCE(p.paid_at, p.created_at) <  CAST(? AS timestamptz)
                """, BigDecimal.class, from, to);
    }

    private static long l(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        throw new IllegalArgumentException("unexpected aggregate: " + v);
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return BigDecimal.valueOf(n.longValue());
        return BigDecimal.ZERO;
    }

    private static int pct(long achieved, int target) {
        if (target <= 0) return 0;
        return (int) Math.round(achieved * 100.0 / target);
    }

    private static int pct(BigDecimal achieved, BigDecimal target) {
        if (target == null || target.signum() <= 0) return 0;
        return achieved.multiply(BigDecimal.valueOf(100))
                .divide(target, 0, RoundingMode.HALF_UP).intValue();
    }
}