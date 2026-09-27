package com.securetravels.crm.trip;

import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.config.AppProperties;
import com.securetravels.crm.common.notify.EmailNotifier;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 3 — capacity alerts, delivered through the SAME in-process path the
 * Phase-1 SLA escalation already uses: a {@code notifications} row (bell) plus
 * an {@link EmailNotifier} call, with an audit entry. No broker, no pub/sub, no
 * queue — a direct DB write inside the caller's transaction, mirroring
 * {@code FollowUpAutomation.escalateToManager} (ADR-0003).
 *
 * <p>Two independent one-shot alerts, each with its own latch column:
 * <ul>
 *   <li><b>Scarcity</b> — fill first reaches {@code app.capacity.alert-fill-percent}
 *       (default 90%). Latched by {@code capacity_alerted_at} (V10). Raised from
 *       the seat-write path in {@code BookingService}.</li>
 *   <li><b>Minimum viable group</b> — a near-departure batch is too thin to run.
 *       Latched by {@code min_group_alerted_at} (V11). Raised by the scheduled
 *       {@link CapacityAlertSweep}.</li>
 * </ul>
 *
 * <p>Methods are {@link Propagation#MANDATORY}: the latch must commit in the
 * same transaction as the seat change that caused it, so a rolled-back booking
 * can never leave a "we already warned ops" latch behind. The latch relies on
 * JPA dirty checking (the batch row is already managed by the caller), which is
 * why there is no explicit save here.
 */
@Service
public class CapacityAlertService {

    private static final Logger log = LoggerFactory.getLogger(CapacityAlertService.class);
    private static final String APP_URL = "http://localhost:3000";

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final EmailNotifier email;
    private final AuditService auditService;
    private final AppProperties props;

    public CapacityAlertService(NotificationRepository notifications, UserRepository users,
                                EmailNotifier email, AuditService auditService, AppProperties props) {
        this.notifications = notifications;
        this.users = users;
        this.email = email;
        this.auditService = auditService;
        this.props = props;
    }

    public int alertFillPercent() {
        return props.getCapacity().getAlertFillPercent();
    }

    // ------------------------------------------------------------ scarcity (M3.3)

    /**
     * Raise the scarcity alert the first time this batch crosses the configured
     * fill threshold. Called from the seat-write path while the batch row lock
     * is held, so the crossing is judged against committed reality.
     *
     * @return true if this call raised the alert
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onSeatsBooked(Batch batch, Trip trip) {
        if (trip == null) {
            // batches.trip_id has no FK constraint, so a dangling batch is possible.
            // Never fail a customer's booking over a notification, and do not
            // latch — if the trip is restored, the alert should still be able to fire.
            log.warn("[capacity] batch={} references missing trip={}; skipping scarcity alert",
                    batch.getId(), batch.getTripId());
            return false;
        }
        if (!batch.needsCapacityAlert()) {
            return false;
        }
        int fill = batch.fillPercent();
        if (fill < alertFillPercent()) {
            return false;
        }

        // Latch first: a re-entrant or retried caller sees "already alerted" and
        // skips, so ops can never be double-notified.
        batch.markCapacityAlerted(Instant.now());

        String title = "Scarcity: " + trip.getName() + " " + batch.getDepartureDate() + " at " + fill + "%";
        String body = "Departure " + batch.getDepartureDate() + " of \"" + trip.getName() + "\" is "
                + fill + "% full (" + batch.getSeatsBooked() + " of " + batch.getMaxCapacity()
                + " seats). " + batch.seatsAvailable() + " seat(s) left.";

        raise(Role.OPS, title, body, "/trips/" + trip.getId());
        auditService.record("BATCH", batch.getId(), AuditAction.UPDATE,
                "capacity_alerted_at", null, String.valueOf(fill) + "%");
        log.info("[capacity] scarcity alert batch={} fill={} threshold={}",
                batch.getId(), fill, alertFillPercent());
        return true;
    }

    // ------------------------------------------------- minimum viable group (M3.4)

    /**
     * Is this batch below the viable-group bar for a near-departure departure?
     * Pure predicate — no side effects, {@code today} injected rather than read
     * from the clock — so the rule is directly unit-testable.
     *
     * <p>Two alternative signals, because either one alone misses real cases:
     * an absolute headcount floor (3 people on a departure that needs 6, however
     * empty the coach) and a fill-percentage floor (6 people on a 100-seat coach
     * technically meets the headcount but is nowhere near viable).
     */
    public boolean isBelowMinimumGroup(Batch batch, LocalDate today) {
        if (batch.getStatus() != Batch.Status.OPEN) {
            return false;
        }
        long daysToDeparture = batch.getDepartureDate().toEpochDay() - today.toEpochDay();
        if (daysToDeparture < 0 || daysToDeparture > props.getCapacity().getMinGroupLeadDays()) {
            return false;
        }
        boolean belowHeadcount = batch.getSeatsBooked() < props.getCapacity().getMinGroupSize();
        boolean belowFill = batch.fillPercent() < props.getCapacity().getMinGroupFillPercent();
        return belowHeadcount || belowFill;
    }

    /**
     * Raise the minimum-viable-group alert once for this batch.
     *
     * @param today evaluation date, injected so the sweep and tests agree
     * @return true if this call raised the alert
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onMinimumGroupRisk(Batch batch, Trip trip, LocalDate today) {
        if (!batch.needsMinGroupAlert()) {
            return false;
        }
        batch.markMinGroupAlerted(Instant.now());

        long days = batch.getDepartureDate().toEpochDay() - today.toEpochDay();
        String title = "At risk: " + trip.getName() + " " + batch.getDepartureDate()
                + " — only " + batch.getSeatsBooked() + " booked";
        String body = "Departure " + batch.getDepartureDate() + " of \"" + trip.getName() + "\" leaves in "
                + days + " day(s) with " + batch.getSeatsBooked() + " of " + batch.getMaxCapacity()
                + " seats booked (" + batch.fillPercent() + "%), below the viable group of "
                + props.getCapacity().getMinGroupSize() + " travellers / "
                + props.getCapacity().getMinGroupFillPercent() + "% fill. Decide: merge, re-market, or cancel.";

        // Near-departure viability is an Admin/Ops decision, so it goes to every
        // holder of both roles rather than the single MANAGER used for lead SLAs.
        raiseAll(List.of(Role.ADMIN, Role.OPS), title, body, "/trips/" + trip.getId());
        auditService.record("BATCH", batch.getId(), AuditAction.UPDATE,
                "min_group_alerted_at", null, String.valueOf(batch.getSeatsBooked()) + "/" + batch.getMaxCapacity());
        log.info("[capacity] min-group alert batch={} booked={} daysOut={}",
                batch.getId(), batch.getSeatsBooked(), days);
        return true;
    }

    // ------------------------------------------------------------------ helpers

    /** Notify the first user holding {@code role}, oldest first (stable choice). */
    private void raise(Role role, String title, String body, String link) {
        User target = users.findFirstByRoleOrderByCreatedAtAsc(role).orElse(null);
        if (target == null) {
            log.warn("[capacity] no user with role {}; alert '{}' recorded in logs only", role, title);
            return;
        }
        raise(target, title, body, link);
    }

    /** Notify every holder of the given roles, de-duplicated, oldest first. */
    private void raiseAll(List<Role> roles, String title, String body, String link) {
        Set<UUID> notified = new LinkedHashSet<>();
        for (Role role : roles) {
            for (User u : users.findAllByRoleOrderByCreatedAtAsc(role)) {
                if (notified.add(u.getId())) {
                    raise(u, title, body, link);
                }
            }
        }
        if (notified.isEmpty()) {
            log.warn("[capacity] no ADMIN/OPS users exist; alert '{}' recorded in logs only", title);
        }
    }

    private void raise(User target, String title, String body, String link) {
        notifications.save(new Notification(target.getId(), Notification.Channel.IN_APP,
                title, body, link));
        email.send(target.getEmail(), title, body + " " + APP_URL + link);
    }
}
