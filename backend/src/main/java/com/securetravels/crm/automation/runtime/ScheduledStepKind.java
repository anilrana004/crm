package com.securetravels.crm.automation.runtime;

/**
 * Why a run is parked on a scheduled step (Phase 6 Module 2).
 *
 * <p>WAIT = a {@code WAIT} action's delay; RETRY = linear backoff before the
 * next attempt of a failed step; APPROVAL = {@code REQUEST_APPROVAL} waiting
 * for a human decision (resumed only by an explicit approve/reject, never by
 * the poller).
 */
public enum ScheduledStepKind {
    WAIT, RETRY, APPROVAL
}