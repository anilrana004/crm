package com.securetravels.crm.automation;

import com.securetravels.crm.automation.event.AutomationEvent;
import com.securetravels.crm.automation.event.AutomationEventRecord;
import com.securetravels.crm.automation.event.DurableEventPublisher;
import com.securetravels.crm.automation.event.EventOutboxStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable publish seam (Phase 6 Module 2): {@code publish} must durably
 * store the event into the outbox (the caller's transaction — this fake store
 * is the seam into it), and a replay of the same event must be a silent no-op
 * rather than a duplicate outbox row. The dedup contract is enforced by the
 * unique {@code event_key} index in Postgres; the publisher must swallow the
 * resulting {@link DataIntegrityViolationException} exactly how a domain
 * service replaying a fact would exercise it.
 */
class EventPublisherTest {

    @Test
    void storesPublishedEventsIntoTheOutbox() {
        RecordingStore store = new RecordingStore();
        DurableEventPublisher publisher = new DurableEventPublisher(store);

        AutomationEvent event = new AutomationEvent("lead", "updated", UUID.randomUUID(), Instant.now());
        publisher.publish(event);

        assertThat(store.saved()).hasSize(1);
        assertThat(store.saved().get(0).getEventKey()).isEqualTo(event.eventKey());
        assertThat(store.saved().get(0).getEntity()).isEqualTo("lead");
        assertThat(store.saved().get(0).getAction()).isEqualTo("updated");
    }

    @Test
    void ignoresNull() {
        RecordingStore store = new RecordingStore();
        new DurableEventPublisher(store).publish(null);
        assertThat(store.saved()).isEmpty();
    }

    @Test
    void treatsADuplicateEventKeyAsACoalescedReplayNotAnError() {
        RecordingStore store = new RecordingStore();
        DurableEventPublisher publisher = new DurableEventPublisher(store);

        AutomationEvent event = new AutomationEvent("lead", "updated", UUID.randomUUID(), Instant.EPOCH);
        publisher.publish(event);
        assertThat(store.saved()).hasSize(1);

        // The idempotency key covers subject+action+instant, so replaying the
        // same fact must be a silent no-op (one outbox row, one collision).
        publisher.publish(event);
        assertThat(store.saved()).hasSize(1);
        assertThat(store.duplicates()).isEqualTo(1);
    }

    /** The seam into the caller's transaction; the unique event_key index
     *  surfaces here as a DataIntegrityViolationException the publisher owns. */
    private static final class RecordingStore implements EventOutboxStore {

        private final List<AutomationEventRecord> saved = new ArrayList<>();
        private final Set<String> keys = new HashSet<>();
        private int duplicates = 0;

        @Override
        public void save(AutomationEventRecord record) {
            if (!keys.add(record.getEventKey())) {
                duplicates++;
                throw new DataIntegrityViolationException("duplicate event_key " + record.getEventKey());
            }
            saved.add(record);
        }

        List<AutomationEventRecord> saved() {
            return saved;
        }

        int duplicates() {
            return duplicates;
        }
    }
}