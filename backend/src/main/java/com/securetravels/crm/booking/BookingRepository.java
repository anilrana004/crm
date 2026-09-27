package com.securetravels.crm.booking;

import com.securetravels.crm.payment.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Query("""
            select b from Booking b
            where (:status is null or b.status = :status)
              and (:tripId is null or b.tripId = :tripId)
              and (:batchId is null or b.batchId = :batchId)
              and (:customerId is null or b.customerId = :customerId)
              and (:createdBy is null or b.createdBy = :createdBy)
            order by b.createdAt desc""")
    List<Booking> search(@Param("status") Booking.Status status,
                         @Param("tripId") UUID tripId,
                         @Param("batchId") UUID batchId,
                         @Param("customerId") UUID customerId,
                         @Param("createdBy") UUID createdBy);

    /**
     * Projection of a customer's trip history: booking ref, trip, status and
     * the amount actually applied to that booking (COMPLETED + PARTIAL).
     */
    interface TripHistoryRow {
        UUID getBookingId();
        String getBookingRef();
        UUID getTripId();
        String getTripName();
        LocalDate getDepartureDate();
        Booking.Status getStatus();
        BigDecimal getNetAmount();
        BigDecimal getAppliedAmount();
    }

    @Query("""
            select b.id as bookingId,
                   b.bookingRef as bookingRef,
                   b.tripId as tripId,
                   (select t.name from Trip t where t.id = b.tripId) as tripName,
                   b.travelDate as departureDate,
                   b.status as status,
                   b.totalAmount as netAmount,
                   (select coalesce(sum(p.amount), 0) from Payment p
                    where p.bookingId = b.id
                      and p.status in (:applied)) as appliedAmount
            from Booking b
            where b.customerId = :customerId and b.status in (:tripStatuses)
            order by b.travelDate desc""")
    List<TripHistoryRow> tripHistory(@Param("customerId") UUID customerId,
                                     @Param("applied") List<Payment.Status> applied,
                                     @Param("tripStatuses") List<Booking.Status> tripStatuses);

    /** The customer's most recent trip date (CONFIRMED/COMPLETED only). */
    @Query("""
            select max(b.travelDate) from Booking b
            where b.customerId = :customerId and b.status in (com.securetravels.crm.booking.Booking.Status.CONFIRMED, com.securetravels.crm.booking.Booking.Status.COMPLETED)""")
    LocalDate lastTripDate(@Param("customerId") UUID customerId);

    /** Next sequential reference number for a given TOH-YYYY- prefix. */
    long countByBookingRefStartingWith(String prefix);

    /** Confirmed bookings on a departure batch (compliance gate scope). */
    List<Booking> findByBatchIdAndStatus(UUID batchId, Booking.Status status);
}