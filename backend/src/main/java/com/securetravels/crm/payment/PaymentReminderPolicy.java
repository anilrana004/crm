package com.securetravels.crm.payment;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Pure reminder-calculation rules (Module 5), separated so they can be unit
 * tested without a Spring context. Mirrors PaymentSweep: a due date at or
 * before today+horizonDays is within the reminder window (overdue lines are
 * still reminded), and the sweep closes outstanding follow-ups after the
 * payment is completed.
 */
public final class PaymentReminderPolicy {

    private PaymentReminderPolicy() {}

    /** true when the line should be chased today (due <= today + horizonDays). */
    public static boolean isDueWithinHorizon(LocalDate due, LocalDate today, int horizonDays) {
        return due != null && !due.isAfter(today.plusDays(horizonDays));
    }

    /** HUMAN-LIKE "N day(s)" / "today" label used by the reminder task title. */
    public static String dueLabel(LocalDate due, LocalDate today) {
        if (due == null) return "today";
        long days = ChronoUnit.DAYS.between(today, due);
        return days > 0 ? days + " day(s)" : "today";
    }
}