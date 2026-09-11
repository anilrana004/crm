package com.securetravels.crm.payment.dto;

import com.securetravels.crm.payment.Payment;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID bookingId,
        String bookingRef,
        String customerName,
        BigDecimal amount,
        Payment.AmountType amountType,
        Payment.Status status,
        LocalDate dueDate,
        Instant paidAt,
        String gatewayRef,
        String notes,
        UUID recordedBy,
        String recordedByName,
        Instant createdAt,
        Instant updatedAt
) {
}