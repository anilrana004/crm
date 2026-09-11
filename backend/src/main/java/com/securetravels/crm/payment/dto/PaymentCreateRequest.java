package com.securetravels.crm.payment.dto;

import com.securetravels.crm.payment.Payment;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Records a payment line against a booking: an advance/deposit, the final
 * balance, or a single full payment. Balance is auto-computed from the
 * booking net amount minus what has actually been applied (summary).
 */
public record PaymentCreateRequest(
        @NotNull(message = "bookingId is required")
        UUID bookingId,

        @NotNull(message = "amount is required")
        @Positive(message = "amount must be greater than zero")
        BigDecimal amount,

        @NotNull(message = "amountType is required")
        Payment.AmountType amountType,

        LocalDate dueDate,

        String gatewayRef,

        String notes
) {
}