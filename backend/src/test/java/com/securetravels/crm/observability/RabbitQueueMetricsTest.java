package com.securetravels.crm.observability;

import com.securetravels.crm.common.config.AppProperties;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Module 5: queue-depth gauge behaviour that does not need a live broker.
 *
 * <p>These are the cases that are easy to get wrong and invisible in
 * production: the gauges must exist even with no broker, a broker outage must
 * not be reported as "queue empty", and INLINE mode must not open a connection
 * (ADR 0005).
 */
class RabbitQueueMetricsTest {

    private static final String QUEUE = "securetravels.whatsapp.dispatch";
    private static final String DLQ = "securetravels.whatsapp.dispatch.dlq";

    private AppProperties props;
    private MeterRegistry registry;
    private ObjectProvider<RabbitAdmin> noAdmin;
    private RabbitQueueMetrics metrics;

    @BeforeEach
    void setUp() {
        props = new AppProperties();
        registry = new SimpleMeterRegistry();
        // An empty provider stands in for "no RabbitAdmin bean exists".
        noAdmin = new ObjectProvider<>() {
            @Override
            public RabbitAdmin getObject() {
                throw new IllegalStateException("no RabbitAdmin");
            }

            @Override
            public RabbitAdmin getObject(Object... args) {
                throw new IllegalStateException("no RabbitAdmin");
            }

            @Override
            public RabbitAdmin getIfAvailable() {
                return null;
            }

            @Override
            public RabbitAdmin getIfUnique() {
                return null;
            }
        };
        metrics = new RabbitQueueMetrics(props, noAdmin, registry);
    }

    @Test
    @DisplayName("depth, consumer, up and staleness meters exist for both queues even with no broker")
    void metersAreRegisteredWithoutABroker() {
        Set<String> names = registry.getMeters().stream()
                .map(m -> m.getId().getName() + "|" + m.getId().getTag("queue"))
                .collect(Collectors.toSet());

        assertThat(names).contains(
                RabbitQueueMetrics.PREFIX + ".depth|" + QUEUE,
                RabbitQueueMetrics.PREFIX + ".depth|" + DLQ,
                RabbitQueueMetrics.PREFIX + ".consumers|" + QUEUE,
                RabbitQueueMetrics.PREFIX + ".consumers|" + DLQ,
                RabbitQueueMetrics.UP_METRIC + "|null",
                RabbitQueueMetrics.AGE_METRIC + "|null");
    }

    @Test
    @DisplayName("INLINE mode reports zero and never contacts the broker")
    void inlineModeDoesNotTouchTheBroker() {
        // A provider that would blow up if it were ever called, on its own
        // registry so the duplicate-registration warning does not fire.
        var local = new SimpleMeterRegistry();
        var m = new RabbitQueueMetrics(props, explodingProvider(), local);

        m.refresh();

        assertThat(m.read(QUEUE)).isZero();
        assertThat(m.read(DLQ)).isZero();
        assertThat(local.get(RabbitQueueMetrics.UP_METRIC).gauge().value()).isZero();
        // Never read the broker, so "staleness" must stay at the no-data sentinel.
        assertThat(m.staleSeconds()).isEqualTo(RabbitQueueMetrics.NEVER_READ_SECONDS);
    }

    @Test
    @DisplayName("stale depth is never rendered as a healthy empty queue")
    void staleIsDistinguishableFromEmpty() {
        metrics.setBrokerObservedOnceForTest(true);
        assertThat(registry.get(RabbitQueueMetrics.UP_METRIC).gauge().value()).isEqualTo(1.0d);

        metrics.setBrokerObservedOnceForTest(false);
        assertThat(registry.get(RabbitQueueMetrics.UP_METRIC).gauge().value()).isZero();
    }

    @Test
    @DisplayName("staleness reports a sentinel before any successful read, then real age")
    void stalenessAge() {
        assertThat(metrics.staleSeconds()).isEqualTo(RabbitQueueMetrics.NEVER_READ_SECONDS);

        metrics.setBrokerObservedOnceForTest(true);
        metrics.markStaleForTest(45);
        assertThat(metrics.staleSeconds()).isBetween(40.0d, 60.0d);
    }

    @Test
    @DisplayName("BROKER mode without a RabbitAdmin bean degrades to up=0 instead of throwing")
    void brokerModeWithoutAdminDoesNotThrow() {
        props.getMessaging().setMode(AppProperties.Messaging.Mode.BROKER);
        var local = new SimpleMeterRegistry();
        var m = new RabbitQueueMetrics(props, noAdmin, local);

        m.refresh(); // must not propagate

        assertThat(local.get(RabbitQueueMetrics.UP_METRIC).gauge().value()).isZero();
    }

    @Test
    @DisplayName("unknown queue names read as zero rather than throwing")
    void unknownQueueReadsZero() {
        assertThat(metrics.read("not.a.real.queue")).isZero();
        assertThat(metrics.consumerRead("not.a.real.queue")).isZero();
    }

    @Test
    @DisplayName("every metric carries a description so the Prometheus endpoint is self-describing")
    void metersAreDescribed() {
        for (Meter m : registry.getMeters()) {
            assertThat(m.getId().getDescription())
                    .as("description for %s", m.getId().getName())
                    .isNotBlank();
        }
    }

    private static ObjectProvider<RabbitAdmin> explodingProvider() {
        return new ObjectProvider<>() {
            @Override
            public RabbitAdmin getObject() {
                throw new AssertionError("INLINE mode must not resolve a RabbitAdmin");
            }

            @Override
            public RabbitAdmin getObject(Object... args) {
                throw new AssertionError("INLINE mode must not resolve a RabbitAdmin");
            }

            @Override
            public RabbitAdmin getIfAvailable() {
                throw new AssertionError("INLINE mode must not resolve a RabbitAdmin");
            }

            @Override
            public RabbitAdmin getIfUnique() {
                throw new AssertionError("INLINE mode must not resolve a RabbitAdmin");
            }
        };
    }
}
