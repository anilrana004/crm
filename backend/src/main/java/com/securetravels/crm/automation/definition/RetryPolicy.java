package com.securetravels.crm.automation.definition;

/**
 * Retry budget for a step (Module 2 execution semantics).
 *
 * <p>{@code maxAttempts = 1} means "try once" (no retry). Backoff is linear
 * between attempts, mirroring the messaging dispatch pattern. Exactly-once
 * effects are guaranteed by the Module 2 outbox/dedup key regardless of how
 * many attempts run, so this controls latency under transient failure but
 * can never double-apply a side effect.
 *
 * @param maxAttempts   1..10; defaults to 1 when absent.
 * @param backoffMillis pause between attempts (>= 0).
 */
public record RetryPolicy(int maxAttempts, long backoffMillis) {

    public static final RetryPolicy NONE = new RetryPolicy(1, 0);
}