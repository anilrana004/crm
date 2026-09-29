package com.securetravels.crm.automation.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The production {@link EventPublisher} (Phase 6 Module 2): a transactional
 * outbox, not a synchronous broadcast.
 *
 * <p>Calling {@link #publish} writes the event as an
 * {@link AutomationEventRecord} IN THE CALLER'S TRANSACTION. Whether the
 * caller is a domain service committing a change or a test calling stand-alone,
 * the record and the business change either both survive or neither does. A
 * power cut after commit leaves the row for the relay; a crash before commit
 * means the change never happened and won't be advertised. The relay drains
 * the outbox afterwards, so the engine's work is decoupled from the request
 * path and the p95 latency of the caller is not hostage to a workflow.
 *
 * <p>{@code event_key} is unique, so re-publishing the same event (replay) is
 * a silent no-op rather than a duplicate run.
 */
@Component
public class DurableEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DurableEventPublisher.class);

    private final EventOutboxStore outbox;

    public DurableEventPublisher(EventOutboxStore outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publish(AutomationEvent event) {
        if (event == null) {
            return;
        }
        try {
            outbox.save(new AutomationEventRecord(event));
        } catch (DataIntegrityViolationException e) {
            // Same subject+action+instant already in the outbox: a replay of an
            // already-recorded event, not a new fact.
            log.debug("[automation] coalesced duplicate event {}", event.eventKey());
        }
    }
}