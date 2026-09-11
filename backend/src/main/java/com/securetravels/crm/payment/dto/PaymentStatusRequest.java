package com.securetravels.crm.payment.dto;

import com.securetravels.crm.payment.Payment;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

/**
 * Moves a payment line along its lifecycle. PENDING, PARTIAL and COMPLETED
 * are progressive; COMPLETED requires a {@code paidAt} (enforced here and by
 * the payments CHECK constraint); COMPLETED may later be REFUNDED.
 */
public record PaymentStatusRequest(
        @NotNull(message = "status is required")
        Payment.Status status,

        Instant paidAt,

        String gatewayRef,

        String note
) {
}