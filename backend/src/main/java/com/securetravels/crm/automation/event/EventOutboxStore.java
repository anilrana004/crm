package com.securetravels.crm.automation.event;

/**
 * Where a published {@link AutomationEvent} is durably stored, in the caller's
 * transaction, before the relay ever sees it (Phase 6 Module 2).
 *
 * <p>Split out so the durable publisher stays unit-testable without a JPA
 * context; the production implementation delegates to
 * {@link AutomationEventRecordRepository}.
 */
public interface EventOutboxStore {

    void save(AutomationEventRecord record);
}