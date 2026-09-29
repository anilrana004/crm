package com.securetravels.crm.automation.event;

/**
 * Port the domain services publish to when a business fact happens.
 *
 * <p>Module 2 wires this to the engine (subscription + durability). The only
 * production implementation is {@link DurableEventPublisher}, which writes the
 * event to the {@code automation_events} outbox in the caller's transaction —
 * nothing can be lost by accident — and the relay drains it afterwards. A test
 * may substitute a recording fake instead.
 */
public interface EventPublisher {

    void publish(AutomationEvent event);
}