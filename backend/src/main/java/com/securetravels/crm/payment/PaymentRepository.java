package com.securetravels.crm.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByBookingIdOrderByCreatedAtAsc(UUID bookingId);

    List<Payment> findByBookingIdAndStatus(UUID bookingId, Payment.Status status);

    /** Lines currently on the books (not cancelled/refunded) for a booking. */
    List<Payment> findByBookingIdAndStatusNotIn(UUID bookingId, List<Payment.Status> statuses);

    /** Pending lines whose due date has already passed (overdue sweep). */
    List<Payment> findByStatusAndDueDateBefore(Payment.Status status, LocalDate before);
}