package com.securetravels.crm.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Discount-approval threshold (Phase 1 hardening). A discount strictly above
 * the configured percentage of the gross booking total still requires a
 * MANAGER/ADMIN/CEO to confirm the booking; a discount at or below the limit
 * is auto-approvable by any writer. A zero or negative "limit" reverts to the
 * original Phase 1 rule (every discounted booking needs a manager).
 */
@Component
public class DiscountPolicy {

    private final BigDecimal maxAutoApprovePercent;

    public DiscountPolicy(@Value("${app.discount.max-auto-approve-percent:5.00}") BigDecimal maxAutoApprovePercent) {
        this.maxAutoApprovePercent = maxAutoApprovePercent;
    }

    /** true when {@code discount} needs a manager to confirm. */
    public boolean requiresManagerApproval(BigDecimal discount, BigDecimal gross) {
        if (discount == null || discount.signum() <= 0) {
            return false;
        }
        if (maxAutoApprovePercent.signum() <= 0) {
            return true;
        }
        if (gross == null || gross.signum() <= 0) {
            return false;
        }
        BigDecimal limit = gross.multiply(maxAutoApprovePercent)
                .divide(BigDecimal.valueOf(100), RoundingMode.DOWN);
        return discount.compareTo(limit) > 0;
    }
}