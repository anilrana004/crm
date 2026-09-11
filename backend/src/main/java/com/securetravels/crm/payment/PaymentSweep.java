package com.securetravels.crm.payment;

import com.securetravels.crm.booking.Booking;
import com.securetravels.crm.booking.BookingRepository;
import com.securetravels.crm.common.audit.AuditService;
import com.securetravels.crm.operations.OperationsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Module 5 — payment maintenance sweep (legacy "payment reminders + overdue
 * sync" cron). Runs daily:
 *   1. Marks PENDING lines past their due date as OVERDUE.
 *   2. Creates a "balance payment due" reminder task for every unpaid line
 *      within the next 3 days (deduped per booking via PaymentService), so
 *      sales chase the balance before the deadline.
 * Directly invokable from tests (runSweep) for deterministic coverage.
 */
@Component
public class PaymentSweep {

    private static final Logger log = LoggerFactory.getLogger(PaymentSweep.class);

    private static final int REMINDER_HORIZON_DAYS = 3;

    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final PaymentService paymentService;
    private final OperationsService operationsService;
    private final AuditService auditService;

    public PaymentSweep(PaymentRepository payments, BookingRepository bookings,
                        PaymentService paymentService, OperationsService operationsService,
                        AuditService auditService) {
        this.payments = payments;
        this.bookings = bookings;
        this.paymentService = paymentService;
        this.operationsService = operationsService;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 90_000)
    @Transactional
    public void runSweep() {
        LocalDate today = LocalDate.now();
        int overdued = 0;
        int reminded = 0;

        List<Payment> pending = payments.findByStatusAndDueDateBefore(Payment.Status.PENDING, today);
        for (Payment p : pending) {
            String old = p.getStatus().name();
            p.setStatus(Payment.Status.OVERDUE);
            payments.save(p);
            auditService.statusChange("PAYMENT", p.getId(), "status", old, Payment.Status.OVERDUE.name());
            overdued++;
        }
        if (overdued > 0) {
            pending.stream().map(Payment::getBookingId).distinct()
                    .forEach(operationsService::syncPaymentStatus);
        }

        List<Payment.Status> unpaid = List.of(Payment.Status.PENDING, Payment.Status.OVERDUE);
        List<Payment> dueSoon = payments.findAll().stream()
                .filter(p -> unpaid.contains(p.getStatus()))
                .filter(p -> PaymentReminderPolicy.isDueWithinHorizon(p.getDueDate(), today, REMINDER_HORIZON_DAYS))
                .toList();
        for (Payment p : dueSoon) {
            Booking booking = bookings.findById(p.getBookingId()).orElse(null);
            if (booking == null) continue;
            paymentService.ensureReminder(booking, p);
            reminded++;
        }

        if (overdued > 0 || reminded > 0) {
            log.info("[payments-sweep] overdued={} reminders={} diff={}", overdued, reminded, today);
        }
    }
}