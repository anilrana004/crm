package com.securetravels.crm.commission;

import com.securetravels.crm.booking.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CommissionLedgerTest {

    /** Booking's no-arg constructor is protected for JPA, so tests build it reflectively. */
    private static Booking booking(String gross, String discount, String tax, Booking.Status status) {
        Booking b;
        try {
            var ctor = Booking.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            b = ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        set(b, "id", UUID.randomUUID());
        set(b, "tripId", UUID.randomUUID());
        set(b, "totalAmount", new BigDecimal(gross));
        // A null String here means "the entity field is null", which is the case
        // nullsAreTreatedAsZero is actually about.
        set(b, "discountAmount", discount == null ? null : new BigDecimal(discount));
        set(b, "taxAmount", tax == null ? null : new BigDecimal(tax));
        set(b, "status", status);
        return b;
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("net amount is gross less discount plus tax")
    void netIsGrossLessDiscountPlusTax() {
        Booking b = booking("100000.00", "5000.00", "8000.00", Booking.Status.CONFIRMED);
        CommissionLedger row = CommissionLedger.credit(b, UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        assertThat(row.getNetAmount()).isEqualByComparingTo("103000.00");
    }

    @Test
    @DisplayName("null discount or tax is treated as zero, not a NullPointerException")
    void nullsAreTreatedAsZero() {
        Booking b = booking("100000.00", null, null, Booking.Status.CONFIRMED);
        CommissionLedger row = CommissionLedger.credit(b, null, null, Instant.now());
        assertThat(row.getNetAmount()).isEqualByComparingTo("100000.00");
        assertThat(row.getLeadId()).isNull();
    }

    @Test
    @DisplayName("the snapshot records the status at credit, not a live relation")
    void snapshotKeepsStatusAtCredit() {
        Booking b = booking("100000.00", "0", "0", Booking.Status.CONFIRMED);
        CommissionLedger row = CommissionLedger.credit(b, UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        assertThat(row.getBookingStatusAtCredit()).isEqualTo(Booking.Status.CONFIRMED);

        // A later COMPLETED transition must not rewrite the credit.
        b.setStatus(Booking.Status.COMPLETED);
        assertThat(row.getBookingStatusAtCredit())
                .as("the credit still says CONFIRMED after the booking moved on")
                .isEqualTo(Booking.Status.CONFIRMED);
    }

    @Test
    @DisplayName("revoke is explicit and reports whether it changed anything")
    void revokeReportsWhetherItChanged() {
        CommissionLedger row = CommissionLedger.credit(
                booking("100000.00", "0", "0", Booking.Status.CONFIRMED),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        assertThat(row.isRevoked()).isFalse();
        assertThat(row.revoke(Instant.now(), "cancelled")).isTrue();
        assertThat(row.isRevoked()).isTrue();
        assertThat(row.getRevokeReason()).isEqualTo("cancelled");
    }

    @Test
    @DisplayName("a second revoke is a no-op, so a retried cancellation cannot corrupt the trail")
    void revokeIsIdempotent() {
        CommissionLedger row = CommissionLedger.credit(
                booking("100000.00", "0", "0", Booking.Status.CONFIRMED),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());

        Instant firstAt = Instant.parse("2026-01-01T00:00:00Z");
        assertThat(row.revoke(firstAt, "first reason")).isTrue();

        // Retry with a different reason and a later timestamp: the original must stand.
        assertThat(row.revoke(Instant.parse("2026-06-01T00:00:00Z"), "second reason")).isFalse();
        assertThat(row.getRevokeReason()).isEqualTo("first reason");
        assertThat(row.getRevokedAt()).isEqualTo(firstAt);
    }

    @Test
    @DisplayName("a revoked credit contributes zero, but keeps its original net amount on record")
    void revokedCreditIsExcludedButNotDestroyed() {
        CommissionLedger row = CommissionLedger.credit(
                booking("100000.00", "0", "0", Booking.Status.CONFIRMED),
                UUID.randomUUID(), UUID.randomUUID(), Instant.now());
        assertThat(row.effectiveNetAmount()).isEqualByComparingTo("100000.00");

        row.revoke(Instant.now(), "cancelled");
        assertThat(row.effectiveNetAmount()).isEqualByComparingTo("0.00");
        assertThat(row.getNetAmount())
                .as("the snapshot itself is never erased; only the effective value changes")
                .isEqualByComparingTo("100000.00");
    }
}
