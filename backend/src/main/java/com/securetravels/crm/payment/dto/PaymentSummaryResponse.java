package com.securetravels.crm.payment.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Receivable statement for one booking: gross, discount, net due, applied
 * (COMPLETED + PARTIAL receipts) and the remaining balance. A negative
 * balance means the booking is over-collected.
 */
public record PaymentSummaryResponse(
        UUID bookingId,
        String bookingRef,
        String customerName,
        BigDecimal grossAmount,
        BigDecimal discountAmount,
        BigDecimal netAmount,
        BigDecimal appliedAmount,
        BigDecimal balanceAmount,
        LocalDate nextDueDate,
        boolean hasOverdueLine
) {
}