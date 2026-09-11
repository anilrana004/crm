package com.securetravels.crm.operations;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.notify.EmailNotifier;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.operations.dto.OpsArrangementsRequest;
import com.securetravels.crm.operations.dto.OpsNoteRequest;
import com.securetravels.crm.operations.dto.OperationsHandoffResponse;
import com.securetravels.crm.payment.Payment;
import com.securetravels.crm.payment.PaymentRepository;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
import com.securetravels.crm.trip.Guide;
import com.securetravels.crm.trip.GuideRepository;
import com.securetravels.crm.trip.Trip;
import com.securetravels.crm.trip.TripRepository;
import com.securetravels.crm.user.Role;
import com.securetravels.crm.user.User;
import com.securetravels.crm.user.UserPrincipal;
import com.securetravels.crm.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 6 — operations handoffs (legacy M11).
 *
 *   • Confirming a booking auto-creates one handoff (invariant I6: unique
 *     booking_id) carrying the ops reference OPS-YYYY-NNNN, travel date, pax,
 *     the batch (FIXED_BATCH) and the auto-synced receivable status, then
 *     notifies every OPS user in-app + email and opens an OPS prep task for
 *     the ops team lead.
 *   • The ops dashboard updates hotel/transport status, assigns a guide or a
 *     driver (Phase-2 vendor id), appends timestamped notes and marks the
 *     trip sheet as generated.
 *   • {@link #syncPaymentStatus} mirrors the booking's receivables onto the
 *     handoff so ops sees advance/balance at a glance (Module 5 integration).
 */
@Service
public class OperationsService {

    private static final Logger log = LoggerFactory.getLogger(OperationsService.class);

    private static final Set<Role> WRITERS = EnumSet.of(Role.OPS, Role.MANAGER, Role.ADMIN, Role.CEO);
    private static final Set<Role> SEES_ALL = EnumSet.of(Role.OPS, Role.MANAGER, Role.ADMIN, Role.CEO);
    private static final String APP_URL = "http://localhost:3000";

    private static final DateTimeFormatter NOTE_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final OperationsHandoffRepository handoffs;
    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final GuideRepository guides;
    private final TripRepository trips;
    private final Customer360Repository customers;
    private final LeadRepository leads;
    private final UserRepository users;
    private final TaskRepository tasks;
    private final NotificationRepository notifications;
    private final EmailNotifier email;
    private final AuditService auditService;

    public OperationsService(OperationsHandoffRepository handoffs, BookingRepository bookings,
                             PaymentRepository payments, GuideRepository guides, TripRepository trips,
                             Customer360Repository customers, LeadRepository leads, UserRepository users,
                             TaskRepository tasks, NotificationRepository notifications, EmailNotifier email,
                             AuditService auditService) {
        this.handoffs = handoffs;
        this.bookings = bookings;
        this.payments = payments;
        this.guides = guides;
        this.trips = trips;
        this.customers = customers;
        this.leads = leads;
        this.users = users;
        this.tasks = tasks;
        this.notifications = notifications;
        this.email = email;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ booking hooks

    /** Invariant I6 — auto-create exactly one handoff the first time a booking is confirmed. */
    @Transactional
    public void onBookingConfirmed(Booking booking, UserPrincipal caller) {
        if (handoffs.existsByBookingId(booking.getId())) {
            return;
        }
        OperationsHandoff handoff = new OperationsHandoff(
                booking.getId(), nextOpsRef(),
                booking.getTravelDate(), booking.getNumTravellers(),
                booking.getBatchId(), null);
        handoff.setPaymentStatus(derivePaymentStatus(booking));
        handoffs.save(handoff);
        auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.CREATE,
                "booking_ref", null, booking.getBookingRef());

        notifyOps(handoff, booking, "New ops handoff " + handoff.getOpsRef(),
                "Booking " + booking.getBookingRef() + " confirmed for "
                        + customerName(booking.getCustomerId()) + " — " + booking.getNumTravellers()
                        + " pax, travel " + booking.getTravelDate() + ". Arrange hotel / transport / guide.");
        openOpsPrepTask(handoff, booking);
        log.info("[operations] handoff={} booking={} travelDate={} pax={} by={}",
                handoff.getOpsRef(), booking.getId(), booking.getTravelDate(), booking.getNumTravellers(), caller.id());
    }

    /** A cancelled booking keeps its handoff as the historical record, annotated. */
    @Transactional
    public void onBookingCancelled(Booking booking) {
        handoffs.findByBookingId(booking.getId()).ifPresent(handoff -> {
            handoff.setNotes(appendNote(handoff.getNotes(), "Booking " + booking.getBookingRef()
                    + " cancelled on " + LocalDate.now()));
            handoffs.save(handoff);
            auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.UPDATE,
                    "note", null, "Booking " + booking.getBookingRef() + " cancelled");
        });
    }

    // ------------------------------------------------------------------ ops dashboard reads

    @Transactional(readOnly = true)
    public List<OperationsHandoffResponse> list(LocalDate travelDateFrom, LocalDate travelDateTo,
                                                OperationsHandoff.HandoffStatus hotelStatus,
                                                OperationsHandoff.HandoffStatus transportStatus,
                                                Payment.Status paymentStatus,
                                                UserPrincipal caller) {
        return handoffs.findAllByOrderByCreatedAtDesc().stream()
                .filter(h -> visible(h.getBookingId(), caller))
                .filter(h -> travelDateFrom == null || h.getTravelDate().isEqual(travelDateFrom)
                        || h.getTravelDate().isAfter(travelDateFrom))
                .filter(h -> travelDateTo == null || h.getTravelDate().isEqual(travelDateTo)
                        || h.getTravelDate().isBefore(travelDateTo))
                .filter(h -> hotelStatus == null || h.getHotelStatus() == hotelStatus)
                .filter(h -> transportStatus == null || h.getTransportStatus() == transportStatus)
                .filter(h -> paymentStatus == null || h.getPaymentStatus() == paymentStatus)
                .map(h -> toResponse(h))
                .toList();
    }

    @Transactional(readOnly = true)
    public OperationsHandoffResponse get(UUID id, UserPrincipal caller) {
        OperationsHandoff handoff = getHandoff(id);
        assertCanRead(handoff.getBookingId(), caller);
        return toResponse(handoff);
    }

    // ------------------------------------------------------------------ ops dashboard writes

    @Transactional
    public OperationsHandoffResponse updateArrangements(UUID id, OpsArrangementsRequest request,
                                                        UserPrincipal caller) {
        requireWriter(caller.role());
        OperationsHandoff handoff = getHandoff(id);
        assertCanRead(handoff.getBookingId(), caller);

        if (request.hotelStatus() != null && request.hotelStatus() != handoff.getHotelStatus()) {
            auditService.statusChange("OPERATIONS_HANDOFF", handoff.getId(), "hotel_status",
                    handoff.getHotelStatus().name(), request.hotelStatus().name());
            handoff.setHotelStatus(request.hotelStatus());
        }
        if (request.transportStatus() != null && request.transportStatus() != handoff.getTransportStatus()) {
            auditService.statusChange("OPERATIONS_HANDOFF", handoff.getId(), "transport_status",
                    handoff.getTransportStatus().name(), request.transportStatus().name());
            handoff.setTransportStatus(request.transportStatus());
        }
        if (request.guideId() != null && !request.guideId().equals(handoff.getGuideId())) {
            if (!guides.existsById(request.guideId())) {
                throw new BadRequestException("Guide not found: " + request.guideId());
            }
            auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.UPDATE, "guide_id",
                    handoff.getGuideId() == null ? null : handoff.getGuideId().toString(),
                    request.guideId().toString());
            handoff.setGuideId(request.guideId());
        }
        if (request.driverId() != null && !request.driverId().equals(handoff.getDriverId())) {
            auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.UPDATE, "driver_id",
                    handoff.getDriverId() == null ? null : handoff.getDriverId().toString(),
                    request.driverId().toString());
            handoff.setDriverId(request.driverId());
        }
        handoffs.save(handoff);
        return toResponse(handoff);
    }

    /** Appends a timestamped, actor-stamped line to the running handoff notes (history preserved). */
    @Transactional
    public OperationsHandoffResponse appendNote(UUID id, OpsNoteRequest request, UserPrincipal caller) {
        requireWriter(caller.role());
        OperationsHandoff handoff = getHandoff(id);
        assertCanRead(handoff.getBookingId(), caller);

        String actor = users.findById(caller.id()).map(User::getFullName).orElse(caller.id().toString());
        String line = "[" + actor + " " + Instant.now().atZone(java.time.ZoneId.systemDefault())
                .format(NOTE_STAMP) + "] " + XssSanitizer.text(request.note().trim());
        handoff.setNotes(appendNote(handoff.getNotes(), line));
        handoffs.save(handoff);
        auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.UPDATE, "note",
                null, XssSanitizer.text(request.note().trim()));
        return toResponse(handoff);
    }

    /** Marks the trip sheet as generated; regenerating simply refreshes the timestamp. */
    @Transactional
    public OperationsHandoffResponse generateTripSheet(UUID id, UserPrincipal caller) {
        requireWriter(caller.role());
        OperationsHandoff handoff = getHandoff(id);
        assertCanRead(handoff.getBookingId(), caller);

        Instant before = handoff.getTripSheetGeneratedAt();
        handoff.setTripSheetGeneratedAt(Instant.now());
        handoffs.save(handoff);
        auditService.record("OPERATIONS_HANDOFF", handoff.getId(), AuditAction.UPDATE,
                "trip_sheet_generated_at",
                before == null ? null : before.toString(),
                handoff.getTripSheetGeneratedAt().toString());
        log.info("[operations] trip-sheet generated handoff={} by={}", handoff.getOpsRef(), caller.id());
        return toResponse(handoff);
    }

    // ------------------------------------------------------------------ Module 5 mirror

    /**
     * Recomputes the handoff's receivable status from the booking's payment
     * lines. Called by PaymentService on every receipt/refund/cancel so the ops
     * dashboard always reflects advances and balances.
     */
    @Transactional
    public void syncPaymentStatus(UUID bookingId) {
        handoffs.findByBookingId(bookingId).ifPresent(handoff -> {
            Booking booking = bookings.findById(bookingId).orElse(null);
            Payment.Status derived = booking == null ? Payment.Status.PENDING : derivePaymentStatus(booking);
            Payment.Status old = handoff.getPaymentStatus();
            if (derived != old) {
                handoff.setPaymentStatus(derived);
                handoffs.save(handoff);
                log.info("[operations] handoff={} paymentStatus {} -> {}", handoff.getOpsRef(), old, derived);
            }
        });
    }

    // ------------------------------------------------------------------ internals

    private Payment.Status derivePaymentStatus(Booking booking) {
        BigDecimal net = booking.netAmount();
        BigDecimal applied = BigDecimal.ZERO;
        boolean overdue = false;
        for (Payment p : payments.findByBookingIdOrderByCreatedAtAsc(booking.getId())) {
            switch (p.getStatus()) {
                case COMPLETED, PARTIAL -> applied = applied.add(p.getAmount());
                case PENDING, OVERDUE -> {
                    if (p.getDueDate() != null && p.getDueDate().isBefore(LocalDate.now())) {
                        overdue = true;
                    }
                }
                default -> { }
            }
        }
        if (applied.compareTo(net) >= 0) {
            return Payment.Status.COMPLETED;
        }
        if (overdue) {
            return Payment.Status.OVERDUE;
        }
        if (applied.signum() > 0) {
            return Payment.Status.PARTIAL;
        }
        return Payment.Status.PENDING;
    }

    private void notifyOps(OperationsHandoff handoff, Booking booking, String title, String body) {
        for (User user : users.findAllByRoleOrderByCreatedAtAsc(Role.OPS)) {
            notifications.save(new Notification(user.getId(), Notification.Channel.IN_APP, title, body,
                    "/operations"));
            email.send(user.getEmail(), title, body + " " + APP_URL + "/operations");
        }
    }

    /** One OPS prep task per booking (open), assigned to the ops team lead. */
    private void openOpsPrepTask(OperationsHandoff handoff, Booking booking) {
        List<Task.Status> open = List.of(Task.Status.PENDING, Task.Status.OVERDUE);
        if (tasks.existsByBookingIdAndTypeAndStatusIn(booking.getId(), Task.Type.OPS, open)) {
            return;
        }
        User lead = users.findFirstByRoleOrderByCreatedAtAsc(Role.OPS).orElse(null);
        if (lead == null) {
            log.warn("[operations] no OPS user to assign prep task handoff={}", handoff.getOpsRef());
            return;
        }
        Instant due = booking.getTravelDate() == null
                ? Instant.now()
                : booking.getTravelDate().minusDays(2).atStartOfDay().atZone(java.time.ZoneId.systemDefault()).toInstant();
        Task task = tasks.save(new Task(booking.getLeadId(), lead.getId(), Task.Type.OPS, due,
                due.plus(48, ChronoUnit.HOURS)));
        task.setBookingId(booking.getId());
        String title = "Ops prep: " + handoff.getOpsRef();
        String body = "Verify hotel/transport and generate the trip sheet for "
                + booking.getBookingRef() + " (" + customerName(booking.getCustomerId()) + ", "
                + booking.getNumTravellers() + " pax, travel " + booking.getTravelDate() + ").";
        notifications.save(new Notification(lead.getId(), Notification.Channel.IN_APP, title, body, "/operations"));
        email.send(lead.getEmail(), title, body + " " + APP_URL + "/operations");
    }

    private String nextOpsRef() {
        int year = LocalDate.now().getYear();
        String prefix = "OPS-" + year + "-";
        return prefix + String.format("%04d", handoffs.countByOpsRefStartingWith(prefix) + 1);
    }

    private static String appendNote(String existing, String line) {
        if (existing == null || existing.isBlank()) {
            return line;
        }
        while (existing.endsWith("\n")) {
            existing = existing.substring(0, existing.length() - 1);
        }
        return existing + "\n" + line;
    }

    private OperationsHandoffResponse toResponse(OperationsHandoff h) {
        Booking booking = bookings.findById(h.getBookingId()).orElse(null);
        Trip trip = booking == null ? null : trips.findById(booking.getTripId()).orElse(null);
        Guide guide = h.getGuideId() == null ? null : guides.findById(h.getGuideId()).orElse(null);
        String createdByName = booking == null || booking.getCreatedBy() == null ? null
                : users.findById(booking.getCreatedBy()).map(User::getFullName).orElse(null);
        return new OperationsHandoffResponse(
                h.getId(), h.getOpsRef(), h.getBookingId(),
                booking == null ? null : booking.getBookingRef(),
                booking == null ? null : booking.getTripId(),
                trip == null ? null : trip.getName(),
                h.getBatchId(), h.getTravelDate(), h.getPax(),
                h.getHotelStatus(), h.getTransportStatus(),
                h.getGuideId(), guide == null ? null : guide.getFullName(),
                h.getDriverId(), h.getPaymentStatus(), h.getTripSheetGeneratedAt(), h.getNotes(),
                booking == null ? null : booking.getCustomerId(),
                customerName(booking == null ? null : booking.getCustomerId()),
                booking == null ? null : booking.getCreatedBy(),
                createdByName, h.getCreatedAt(), h.getUpdatedAt());
    }

    private String customerName(UUID customerId) {
        if (customerId == null) return null;
        Customer360 c = customers.findById(customerId).orElse(null);
        return c == null ? null : c.getFullName();
    }

    private boolean visible(UUID bookingId, UserPrincipal caller) {
        try {
            assertCanRead(bookingId, caller);
            return true;
        } catch (ForbiddenException e) {
            return false;
        }
    }

    private void assertCanRead(UUID bookingId, UserPrincipal caller) {
        if (SEES_ALL.contains(caller.role())) return;
        if (caller.role() == Role.SALES) {
            Booking booking = bookings.findById(bookingId).orElse(null);
            if (booking != null && booking.getCreatedBy() != null && booking.getCreatedBy().equals(caller.id())) {
                return;
            }
            if (booking != null && booking.getLeadId() != null) {
                Lead lead = leads.findById(booking.getLeadId()).orElse(null);
                if (lead != null && caller.id().equals(lead.getOwnerId())) return;
            }
            throw new ForbiddenException("You can only view ops handoffs for bookings you created");
        }
        throw new ForbiddenException("This role (" + caller.role() + ") cannot view ops handoffs");
    }

    private OperationsHandoff getHandoff(UUID id) {
        return handoffs.findById(id).orElseThrow(() -> new NotFoundException("Ops handoff not found: " + id));
    }

    private static void requireWriter(Role role) {
        if (!WRITERS.contains(role)) {
            throw new ForbiddenException("This role (" + role + ") cannot modify ops handoffs");
        }
    }
}