package com.securetravels.crm.operations.dto;

import com.securetravels.crm.operations.OperationsHandoff.HandoffStatus;
import com.securetravels.crm.payment.Payment;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Ops handoff read model. Guides/operators need the booking context (ref,
 * customer, package, pax, travel date) plus the arrangement statuses and the
 * auto-synced receivable status without leaving the ops dashboard.
 */
public record OperationsHandoffResponse(
        UUID id,
        String opsRef,
        UUID bookingId,
        String bookingRef,
        UUID tripId,
        String tripName,
        UUID batchId,
        LocalDate travelDate,
        int pax,
        HandoffStatus hotelStatus,
        HandoffStatus transportStatus,
        UUID guideId,
        String guideName,
        UUID driverId,
        Payment.Status paymentStatus,
        Instant tripSheetGeneratedAt,
        String notes,
        UUID customerId,
        String customerName,
        UUID createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt) {
}