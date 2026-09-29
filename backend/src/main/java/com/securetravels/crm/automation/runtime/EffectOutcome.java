package com.securetravels.crm.automation.runtime;

/**
 * The executor's verdict on one step (Phase 6 Module 2).
 *
 * <p>A SKIPPED outcome is data, not failure: the send gate declined the
 * message, the tag was already present, the action has no subject mapping. The
 * run records the step as SKIPPED with the reason and marches on; only a
 * thrown runtime exception becomes a retry / failure-inbox record.
 */
public record EffectOutcome(boolean completed, boolean skipped, String skipReason) {

    public static EffectOutcome applied() {
        return new EffectOutcome(true, false, null);
    }

    public static EffectOutcome skipped(String reason) {
        return new EffectOutcome(false, true, reason);
    }
}