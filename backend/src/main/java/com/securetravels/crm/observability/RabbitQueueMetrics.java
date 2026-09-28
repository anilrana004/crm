package com.securetravels.crm.observability;

import com.securetravels.crm.common.config.AppProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 Module 5: RabbitMQ queue-depth and dead-letter-depth gauges.
 *
 * <p>Queue depth is the only way to see a <em>growing</em> dead-letter queue
 * before customers notice, so it is published as a gauge rather than left to
 * the operator to inspect the broker by hand.
 *
 * <p>Three deliberate design choices:
 *
 * <ol>
 *   <li><b>Scrape never touches the broker.</b> Meters are registered once and
 *       their values are overwritten by a {@code @Scheduled} sweep. Reading
 *       {@code getQueueInfo()} inside the gauge supplier would issue a passive
 *       queue-declare to RabbitMQ on every Prometheus scrape, coupling scrape
 *       latency and broker load to the scrape interval.</li>
 *   <li><b>INLINE mode never opens a connection.</b> The sweep returns early
 *       unless {@code app.messaging.mode=BROKER}, preserving ADR 0005: with no
 *       broker configured the app must have no broker dependency, and metrics
 *       must not become the thing that introduces one.</li>
 *   <li><b>Stale is not the same as zero.</b> When the broker is unreachable the
 *       last known depth is retained and
 *       {@code securetravels_rabbitmq_queue_metrics_up} drops to 0, so a
 *       dashboard shows "0 messages" as an outage rather than as good news.</li>
 * </ol>
 */
@Component
public class RabbitQueueMetrics {

    private static final Logger log = LoggerFactory.getLogger(RabbitQueueMetrics.class);

    public static final String PREFIX = "securetravels.rabbitmq.queue";
    static final String UP_METRIC = PREFIX + ".metrics.up";
    static final String AGE_METRIC = PREFIX + ".metrics.stale.seconds";

    /**
     * Sentinel staleness (about 31 years) meaning "the broker has never been
     * read". Finite so it renders legibly and cannot overflow an alert
     * expression.
     */
    static final double NEVER_READ_SECONDS = 1_000_000_000d;

    private final AppProperties props;
    private final ObjectProvider<RabbitAdmin> rabbitAdmin;

    /** queue name -> last observed depth. Written only by the sweep. */
    private final Map<String, Integer> depth = new LinkedHashMap<>();
    private final Map<String, Integer> consumers = new LinkedHashMap<>();
    private volatile long lastSuccessNanos;
    private volatile boolean brokerObservedOnce;

    public RabbitQueueMetrics(AppProperties props, ObjectProvider<RabbitAdmin> rabbitAdmin,
                              MeterRegistry registry) {
        this.props = props;
        this.rabbitAdmin = rabbitAdmin;

        // Registered unconditionally, including in INLINE mode: a dashboard
        // panel that vanishes when the queue does is impossible to alert on,
        // and a silent mode flip back to INLINE would otherwise look healthy.
        for (String queue : monitoredQueues()) {
            Gauge.builder(PREFIX + ".depth", this, m -> m.read(queue))
                    .description("Messages ready for delivery (ready + unacked) on the queue")
                    .tag("queue", queue)
                    .register(registry);
            Gauge.builder(PREFIX + ".consumers", this, m -> m.consumerRead(queue))
                    .description("Active consumers on the queue")
                    .tag("queue", queue)
                    .register(registry);
        }
        Gauge.builder(UP_METRIC, this, m -> m.brokerObservedOnce ? 1 : 0)
                .description("1 when the last sweep read the broker, 0 when the depth shown is stale or the mode is INLINE")
                .register(registry);
        Gauge.builder(AGE_METRIC, this, m -> m.staleSeconds())
                .description("Seconds since the broker was last read successfully; large values mean the depth is stale")
                .register(registry);
    }

    private List<String> monitoredQueues() {
        return List.of(props.getMessaging().getQueue(),
                props.getMessaging().getDeadLetterQueue());
    }

    /**
     * One management round-trip per sweep, not per gauge read.
     *
     * <p>Never throws: a broker outage must not take down the scheduler thread
     * (which would silently stop every other sweep in the application) nor the
     * scrape endpoint.
     */
    @Scheduled(fixedDelayString = "${app.observability.queue-sweep-millis:15000}",
               initialDelayString = "${app.observability.queue-sweep-millis:15000}")
    public void refresh() {
        if (props.getMessaging().getMode() != AppProperties.Messaging.Mode.BROKER) {
            depth.clear();
            consumers.clear();
            brokerObservedOnce = false;
            return;
        }
        RabbitAdmin admin = rabbitAdmin.getIfAvailable();
        if (admin == null) {
            brokerObservedOnce = false;
            log.debug("No RabbitAdmin bean available; rabbit queue depth metrics are unavailable");
            return;
        }
        for (String queue : monitoredQueues()) {
            try {
                // Passive declare: never creates the queue as a side effect of
                // measuring it, and throws if the topology is missing.
                var info = admin.getQueueInfo(queue);
                if (info == null) {
                    // Declared but absent (e.g. wiped broker) - report zero, and
                    // keep `up` at 0 so it is not mistaken for a healthy empty queue.
                    depth.put(queue, 0);
                    consumers.put(queue, 0);
                    continue;
                }
                depth.put(queue, info.getMessageCount());
                consumers.put(queue, info.getConsumerCount());
                lastSuccessNanos = System.nanoTime();
                brokerObservedOnce = true;
            } catch (Exception e) {
                // Retain the last known value; `up`/AGE report the staleness.
                log.warn("Could not read RabbitMQ queue depth for {}: {}", queue, e.toString());
                brokerObservedOnce = false;
            }
        }
    }

    int read(String queue) {
        return depth.getOrDefault(queue, 0);
    }

    int consumerRead(String queue) {
        return consumers.getOrDefault(queue, 0);
    }

    /**
     * Age of the last successful broker read.
     *
     * <p>Reports {@link #NEVER_READ_SECONDS} rather than 0 before the first
     * read, so "no data yet" can never be mistaken for "just refreshed" by an
     * alert using {@code > 300}. A large finite value rather than
     * {@code Double.MAX_VALUE} so it stays readable in a dashboard.
     */
    double staleSeconds() {
        long last = lastSuccessNanos;
        if (last == 0L) {
            return NEVER_READ_SECONDS;
        }
        return (System.nanoTime() - last) / 1_000_000_000.0d;
    }

    /** Test seam: pretend the last successful read was {@code seconds} ago. */
    void markStaleForTest(double seconds) {
        this.lastSuccessNanos = System.nanoTime() - (long) (seconds * 1_000_000_000L);
    }

    /** Test seam: force the `up` gauge without needing a live broker. */
    void setBrokerObservedOnceForTest(boolean value) {
        this.brokerObservedOnce = value;
    }
}
