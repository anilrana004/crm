package com.securetravels.crm.booking;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DiscountPolicyTest {

    private final DiscountPolicy fivePercent = new DiscountPolicy(new BigDecimal("5"));

    @Test
    void noDiscountNeverNeedsApproval() {
        assertThat(fivePercent.requiresManagerApproval(null, new BigDecimal("50000"))).isFalse();
        assertThat(fivePercent.requiresManagerApproval(BigDecimal.ZERO, new BigDecimal("50000"))).isFalse();
    }

    @Test
    void atExactLimitIsAutoApprovable() {
        // 5% of a 50,000 gross is exactly 2,500 — still within the limit.
        assertThat(fivePercent.requiresManagerApproval(new BigDecimal("2500"), new BigDecimal("50000"))).isFalse();
    }

    @Test
    void justBelowLimitIsAutoApprovable() {
        assertThat(fivePercent.requiresManagerApproval(new BigDecimal("2499.99"), new BigDecimal("50000"))).isFalse();
    }

    @Test
    void justOverLimitNeedsManager() {
        assertThat(fivePercent.requiresManagerApproval(new BigDecimal("2500.01"), new BigDecimal("50000"))).isTrue();
    }

    @Test
    void biggerPercentToleratesLargerDiscounts() {
        DiscountPolicy twentyPercent = new DiscountPolicy(new BigDecimal("20"));
        assertThat(twentyPercent.requiresManagerApproval(new BigDecimal("10000"), new BigDecimal("50000"))).isFalse();
        assertThat(twentyPercent.requiresManagerApproval(new BigDecimal("10000.01"), new BigDecimal("50000"))).isTrue();
    }

    @Test
    void zeroPercentLimitRevertsToEveryDiscountNeedsManager() {
        DiscountPolicy zeroLimit = new DiscountPolicy(BigDecimal.ZERO);
        assertThat(zeroLimit.requiresManagerApproval(new BigDecimal("1"), new BigDecimal("50000"))).isTrue();
        assertThat(zeroLimit.requiresManagerApproval(new BigDecimal("0.01"), new BigDecimal("50000"))).isTrue();
        assertThat(zeroLimit.requiresManagerApproval(BigDecimal.ZERO, new BigDecimal("50000"))).isFalse();
    }

    @Test
    void missingGrossDoesNotForceApproval() {
        assertThat(fivePercent.requiresManagerApproval(new BigDecimal("100"), null)).isFalse();
    }
}