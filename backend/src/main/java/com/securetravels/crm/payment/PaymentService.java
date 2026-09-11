package com.securetravels.crm.payment;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.common.audit.AuditAction;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.common.exception.BadRequestException;
import com.securetravels.crm.common.exception.ConflictException;
import com.securetravels.crm.common.exception.ForbiddenException;
import com.securetravels.crm.common.exception.NotFoundException;
import com.securetravels.crm.common.notify.EmailNotifier;
import com.securetravels.crm.common.util.XssSanitizer;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.customer.Customer360Service;
import com.securetravels.crm.lead.Lead;
import com.securetravels.crm.lead.LeadRepository;
import com.securetravels.crm.notification.Notification;
import com.securetravels.crm.notification.NotificationRepository;
import com.securetravels.crm.operations.OperationsService;
import com.securetravels.crm.payment.dto.PaymentCreateRequest;
import com.securetravels.crm.payment.dto.PaymentResponse;
import com.securetravels.crm.payment.dto.PaymentStatusRequest;
import com.securetravels.crm.payment.dto.PaymentSummaryResponse;
import com.securetravels.crm.task.Task;
import com.securetravels.crm.task.TaskRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Module 5 — payment tracking & receivable statements.
 *
 *   • A payment line (ADVANCE / BALANCE / FULL) is recorded against a booking
 *     with an optional due date; the balance is always auto-computed from the
 *     booking net amount minus what has actually been applied.
 *   • Receipts: PENDING → PARTIAL → COMPLETED (COMPLETED requires paidAt, as
 *     the payments CHECK enforces); COMPLETED/PARTIAL → REFUNDED.
 *   • PaymentSweep creates "balance due" reminders from T-3 days and flags
 *     OVERDUE once the due date passes; completing a payment retires the open
 *     reminders for that booking.
 *   • Cancelling a booking auto-cancels its PENDING lines (collected money
 *     stays on the books until explicitly refunded).
 * Only the gateway reference is stored — never raw card data.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private static final Set<Role> WRITERS = EnumSet.of(Role.SALES, Role.MANAGER, Role.ADMIN, Role.CEO);
    private static final Set<Role> SEES_ALL = EnumSet.of(Role.MANAGER, Role.ADMIN, Role.CEO, Role.OPS);
    private static final String APP_URL = "http://localhost:3000";

    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final Customer360Repository customers;
    private final LeadRepository leads;
    private final UserRepository users;
    private final TaskRepository tasks;
    private final NotificationRepository notifications;
    private final EmailNotifier email;
    private final AuditService auditService;
    private final OperationsService operationsService;
    private final Customer360Service customerService;

    public PaymentService(PaymentRepository payments, BookingRepository bookings,
                          Customer360Repository customers, LeadRepository leads, UserRepository users,
                          TaskRepository tasks, NotificationRepository notifications, EmailNotifier email,
                          AuditService auditService, OperationsService operationsService,
                          Customer360Service customerService) {
        this.payments = payments;
        this.bookings = bookings;
        this.customers = customers;
        this.leads = leads;
        this.users = users;
        this.tasks = tasks;
        this.notifications = notifications;
        this.email = email;
        this.auditService = auditService;
        this.operationsService = operationsService;
        this.customerService = customerService;
    }

    @Transactional
    public PaymentResponse record(PaymentCreateRequest request, UserPrincipal caller) {
        requireWriter(caller.role());
        Booking booking = bookings.findById(request.bookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found: " + request.bookingId()));
        if (booking.getStatus() == Booking.Status.CANCELLED) {
            throw new BadRequestException("Cannot record a payment on a cancelled booking");
        }
        assertCanAccess(booking, caller);

        Payment payment = new Payment(booking.getId(), request.amount(), request.amountType(), request.dueDate());
        payment.setStatus(Payment.Status.PENDING);
        payment.setRecordedBy(caller.id());
        payment.setGatewayRef(XssSanitizer.text(request.gatewayRef()));
        payment.setNotes(XssSanitizer.text(request.notes()));
        payments.save(payment);

        auditService.record("PAYMENT", payment.getId(), AuditAction.CREATE, "amount_type",
                null, payment.getAmountType().name() + " " + payment.getAmount() + " (" + payment.getStatus() + ")");
        log.info("[payments] booking={} amount={} type={} by={}", booking.getId(), payment.getAmount(),
                payment.getAmountType(), caller.id());
        return toResponse(payment);
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> list(UUID bookingId, Payment.Status status, Payment.AmountType amountType,
                                      UserPrincipal caller) {
        List<Payment> all;
        if (bookingId != null) {
            Booking booking = bookings.findById(bookingId)
                    .orElseThrow(() -> new NotFoundException("Booking not found: " + bookingId));
            assertCanRead(booking, caller);
            all = payments.findByBookingIdOrderByCreatedAtAsc(bookingId);
        } else {
            all = payments.findAll().stream()
                    .filter(p -> bookingVisible(p.getBookingId(), caller))
                    .toList();
        }
        return all.stream()
                .filter(p -> status == null || p.getStatus() == status)
                .filter(p -> amountType == null || p.getAmountType() == amountType)
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentSummaryResponse summary(UUID bookingId, UserPrincipal caller) {
        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("Booking not found: " + bookingId));
        assertCanRead(booking, caller);

        BigDecimal applied = BigDecimal.ZERO;
        LocalDate nextDue = null;
        boolean overdue = false;
        for (Payment p : payments.findByBookingIdOrderByCreatedAtAsc(bookingId)) {
            switch (p.getStatus()) {
                case COMPLETED, PARTIAL -> applied = applied.add(p.getAmount());
                case REFUNDED, CANCELLED, PENDING, OVERDUE -> { /* not currently applied */ }
            }
            if ((p.getStatus() == Payment.Status.PENDING || p.getStatus() == Payment.Status.OVERDUE)
                    && p.getDueDate() != null) {
                if (p.getDueDate().isBefore(LocalDate.now())) {
                    overdue = true;
                } else if (nextDue == null || p.getDueDate().isBefore(nextDue)) {
                    nextDue = p.getDueDate();
                }
            }
        }
        BigDecimal net = booking.netAmount();
        return new PaymentSummaryResponse(booking.getId(), booking.getBookingRef(), customerName(booking.getCustomerId()),
                booking.getTotalAmount(), booking.getDiscountAmount(), net, applied, net.subtract(applied),
                nextDue, overdue);
    }

    @Transactional
    public PaymentResponse transition(UUID paymentId, PaymentStatusRequest request, UserPrincipal caller) {
        requireWriter(caller.role());
        Payment payment = payments.findById(paymentId)
                .orElseThrow(() -> new NotFoundException("Payment not found: " + paymentId));
        Booking booking = bookings.findById(payment.getBookingId())
                .orElseThrow(() -> new NotFoundException("Booking not found: " + payment.getBookingId()));
        assertCanAccess(booking, caller);

        Payment.Status from = payment.getStatus();
        Payment.Status to = request.status();
        if (from == to) {
            throw new ConflictException("Payment is already " + from);
        }
        boolean allowed = switch (from) {
            case PENDING -> to == Payment.Status.PARTIAL || to == Payment.Status.COMPLETED
                    || to == Payment.Status.CANCELLED;
            case PARTIAL -> to == Payment.Status.COMPLETED || to == Payment.Status.CANCELLED
                    || to == Payment.Status.REFUNDED;
            case COMPLETED -> to == Payment.Status.REFUNDED;
            default -> false;
        };
        if (!allowed) {
            throw new ConflictException("Illegal payment transition " + from + " -> " + to);
        }
        if (to == Payment.Status.COMPLETED && request.paidAt() == null) {
            throw new BadRequestException("paidAt is required when marking a payment COMPLETED");
        }

        String oldStatus = from.name();
        payment.setStatus(to);
        if (request.paidAt() != null) {
            payment.setPaidAt(request.paidAt());
        }
        if (to == Payment.Status.COMPLETED && payment.getPaidAt() == null) {
            payment.setPaidAt(Instant.now());
        }
        if (request.gatewayRef() != null && !request.gatewayRef().isBlank()) {
            payment.setGatewayRef(XssSanitizer.text(request.gatewayRef()));
        }
        if (request.note() != null && !request.note().isBlank()) {
            payment.setNotes(XssSanitizer.text(request.note()));
        }
        payments.save(payment);
        auditService.statusChange("PAYMENT", payment.getId(), "status", oldStatus, to.name());
        if (request.note() != null && !request.note().isBlank()) {
            auditService.record("PAYMENT", payment.getId(), AuditAction.UPDATE, "note", null,
                    XssSanitizer.text(request.note().trim()));
        }

        if (to == Payment.Status.COMPLETED || to == Payment.Status.PARTIAL || to == Payment.Status.CANCELLED) {
            retireReminders(booking.getId());
        }
        operationsService.syncPaymentStatus(booking.getId());
        customerService.maintainAggregates(booking.getCustomerId());
        log.info("[payments] booking={} payment={} {} -> {} by={}", booking.getId(), paymentId, oldStatus, to, caller.id());
        return toResponse(payment);
    }

    /** Public so the reminder sweep can create tasks without coupling to this service's DTOs. */
    @Transactional
    public void ensureReminder(Booking booking, Payment payment) {
        List<Task.Status> open = List.of(Task.Status.PENDING, Task.Status.OVERDUE);
        if (tasks.existsByBookingIdAndTypeAndStatusIn(booking.getId(), Task.Type.PAYMENT_REMINDER, open)) {
            return;
        }
        UUID assignee = booking.getCreatedBy();
        if (assignee == null && booking.getLeadId() != null) {
            Lead lead = leads.findById(booking.getLeadId()).orElse(null);
            assignee = lead == null ? null : lead.getOwnerId();
        }
        if (assignee == null) {
            assignee = users.findFirstByRoleOrderByCreatedAtAsc(Role.MANAGER).map(User::getId).orElse(null);
        }
        if (assignee == null) {
            log.warn("[payments] no assignee for payment reminder booking={}", booking.getId());
            return;
        }
        String when = PaymentReminderPolicy.dueLabel(payment.getDueDate(), LocalDate.now());
        Task task = tasks.save(new Task(booking.getLeadId(), assignee, Task.Type.PAYMENT_REMINDER,
                Instant.now(), Instant.now().plus(24, ChronoUnit.HOURS)));
        task.setBookingId(booking.getId());
        String title = "Balance payment due in " + when;
        String body = "Booking " + booking.getBookingRef() + " has a " + payment.getAmount()
                + " " + payment.getAmountType() + " payment due"
                + (payment.getDueDate() == null ? "" : " on " + payment.getDueDate());
        String link = "/payments?bookingId=" + booking.getId();
        notifications.save(new Notification(assignee, Notification.Channel.IN_APP, title, body, link));
        users.findById(assignee).ifPresent(u -> email.send(u.getEmail(), title, body + " " + APP_URL + link));
        log.info("[payments] reminder created booking={} dueIn={} assignee={}", booking.getId(), when, assignee);
    }

    // ------------------------------------------------------------------ booking hooks

    /** PENDING lines can no longer be collected once the booking is cancelled. */
    @Transactional
    public void autoCancelForBooking(UUID bookingId, String reason) {
        List<Payment> pending = payments.findByBookingIdAndStatus(bookingId, Payment.Status.PENDING);
        for (Payment p : pending) {
            String old = p.getStatus().name();
            p.setStatus(Payment.Status.CANCELLED);
            String suffix = (reason == null || reason.isBlank() || "null".equals(reason)) ? "" : " — " + reason;
            p.setNotes("Booking cancelled" + suffix);
            payments.save(p);
            auditService.statusChange("PAYMENT", p.getId(), "status", old, Payment.Status.CANCELLED.name());
        }
        if (!pending.isEmpty()) {
            retireReminders(bookingId);
            log.info("[payments] booking={} auto-cancelled {} pending line(s)", bookingId, pending.size());
        }
        if (!pending.isEmpty()) {
            operationsService.syncPaymentStatus(bookingId);
            bookings.findById(bookingId).ifPresent(b -> customerService.maintainAggregates(b.getCustomerId()));
        }
    }

    // ------------------------------------------------------------------ internals

    private void assertCanRead(Booking booking, UserPrincipal caller) {
        if (SEES_ALL.contains(caller.role())) return;
        if (caller.role() == Role.SALES) {
            if (booking.getCreatedBy() != null && booking.getCreatedBy().equals(caller.id())) return;
            if (booking.getLeadId() != null) {
                Lead lead = leads.findById(booking.getLeadId()).orElse(null);
                if (lead != null && caller.id().equals(lead.getOwnerId())) return;
            }
            throw new ForbiddenException("You can only view payments for bookings you created");
        }
        throw new ForbiddenException("This role (" + caller.role() + ") cannot view payments");
    }

    private void assertCanAccess(Booking booking, UserPrincipal caller) {
        if (booking.getCreatedBy() != null && booking.getCreatedBy().equals(caller.id())) return;
        if (SEES_ALL.contains(caller.role())) return;
        throw new ForbiddenException("You can only manage payments for bookings you created");
    }

    private boolean bookingVisible(UUID bookingId, UserPrincipal caller) {
        try {
            Booking booking = bookings.findById(bookingId).orElse(null);
            if (booking == null) return false;
            assertCanRead(booking, caller);
            return true;
        } catch (ForbiddenException e) {
            return false;
        }
    }

    private void retireReminders(UUID bookingId) {
        List<Task.Status> open = List.of(Task.Status.PENDING, Task.Status.OVERDUE);
        for (Task t : tasks.findByBookingIdAndTypeAndStatusIn(bookingId, Task.Type.PAYMENT_REMINDER, open)) {
            t.cancel();
        }
    }

    private PaymentResponse toResponse(Payment p) {
        Booking booking = bookings.findById(p.getBookingId()).orElse(null);
        User actor = p.getRecordedBy() == null ? null : users.findById(p.getRecordedBy()).orElse(null);
        return new PaymentResponse(p.getId(), p.getBookingId(),
                booking == null ? null : booking.getBookingRef(),
                booking == null ? null : customerName(booking.getCustomerId()),
                p.getAmount(), p.getAmountType(), p.getStatus(), p.getDueDate(), p.getPaidAt(),
                p.getGatewayRef(), p.getNotes(), p.getRecordedBy(),
                actor == null ? null : actor.getFullName(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    private String customerName(UUID customerId) {
        if (customerId == null) return null;
        Customer360 c = customers.findById(customerId).orElse(null);
        return c == null ? null : c.getFullName();
    }

    private static void requireWriter(Role role) {
        if (!WRITERS.contains(role)) {
            throw new ForbiddenException("This role (" + role + ") cannot manage payments");
        }
    }
}