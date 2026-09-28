package com.securetravels.crm.observability;

import com.securetravels.crm.BaseIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Module 5 against a real broker: the dead-letter gauge must report the
 * broker's actual depth.
 *
 * <p>The alert the roadmap asks for is "growing dead-letter queue", and a gauge
 * that is always 0 satisfies it on paper while paging nobody. This publishes
 * real messages into the real DLQ and asserts the gauge follows the broker.
 * Skips itself when no broker is listening, like {@code WhatsAppBrokerIT}.
 *
 * <p><b>Why the assertions compare against the broker rather than a hard-coded
 * count.</b> The DLQ is a real queue on a real broker that other tests and a
 * running development instance share. An assertion of exactly "3" is therefore
 * a race with any concurrent drain, and it failed in a full-suite run having
 * observed 1. Asking the broker what it actually holds, and asserting the gauge
 * equals that, tests the thing this class exists to test — the gauge does not
 * invent or lose depth — and cannot flake.
 *
 * <p>The sweep interval is pushed to 10 minutes so the only thing that can
 * update the gauges is the explicit {@link RabbitQueueMetrics#refresh()} call,
 * removing the background sweep as a second writer.
 */
@TestPropertySource(properties = {
        "app.messaging.mode=BROKER",
        "app.observability.queue-sweep-millis=600000",
        "app.whatsapp.mode=SANDBOX"
})
class RabbitQueueMetricsBrokerIT extends BaseIT {

    private static final String DISPATCH = "securetravels.whatsapp.dispatch";
    private static final String DLQ = "securetravels.whatsapp.dispatch.dlq";

    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitQueueMetrics metrics;
    @Autowired io.micrometer.core.instrument.MeterRegistry registry;

    @BeforeEach
    void requireBroker() {
        assumeTrue(brokerIsListening(), "RabbitMQ is not listening on 127.0.0.1:5672; skipping");
        rabbitAdmin.purgeQueue(DLQ, false);
        metrics.refresh();
    }

    @Test
    @DisplayName("dead-letter depth gauge tracks the broker's real DLQ depth")
    void deadLetterGaugeTracksRealDepth() {
        assertThat(gaugeDepth(DLQ))
                .as("DLQ starts empty")
                .isEqualTo(0.0d);

        publishToDlq(3);
        awaitBrokerDepth(DLQ, 3);

        metrics.refresh();

        assertThat(gaugeDepth(DLQ))
                .as("gauge must equal the depth the broker reports")
                .isEqualTo((double) brokerDepth(DLQ));
        assertThat(gaugeDepth(DLQ))
                .as("and the broker really is holding the 3 published messages")
                .isEqualTo(3.0d);

        assertThat(registry.get(RabbitQueueMetrics.UP_METRIC).gauge().value())
                .as("a successful read must set the up gauge")
                .isEqualTo(1.0d);

        assertThat(metrics.staleSeconds())
                .as("freshly read, so not stale")
                .isLessThan(5.0d);
    }

    /**
     * Wait until the broker has actually enqueued the published messages.
     *
     * <p>This is synchronisation, not a fudge. {@code RabbitTemplate.send}
     * returns once the publish frame is written; the broker processes channels
     * independently, so a passive declare issued immediately afterwards on the
     * admin's channel can observe a partial count. Measured directly: after
     * publishing 3, an immediate read returned 1, and the same read a moment
     * later returned 3. Asserting the gauge against a broker that had not yet
     * settled would test the timing of AMQP frame delivery instead of the gauge.
     */
    private void awaitBrokerDepth(String queue, int expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        int seen;
        do {
            seen = brokerDepth(queue);
            if (seen == expected) {
                return;
            }
            sleep(50);
        } while (System.nanoTime() < deadline);

        throw new AssertionError("Broker never reported " + expected + " messages on "
                + queue + "; last saw " + seen
                + ". A live consumer may be draining this queue.");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the broker to settle", e);
        }
    }

    @Test
    @DisplayName("gauge returns to zero once the DLQ is drained")
    void depthReturnsToZeroAfterDrain() {
        publishToDlq(1);
        metrics.refresh();
        assertThat(gaugeDepth(DLQ)).isEqualTo((double) brokerDepth(DLQ));

        rabbitAdmin.purgeQueue(DLQ, false);
        metrics.refresh();

        assertThat(gaugeDepth(DLQ))
                .as("a drained queue must not keep reporting its previous depth")
                .isEqualTo(0.0d);
    }

    @Test
    @DisplayName("both monitored queues are measured, not just the dead-letter one")
    void bothQueuesAreMeasured() {
        metrics.refresh();

        for (String queue : new String[]{DISPATCH, DLQ}) {
            assertThat(gaugeDepth(queue))
                    .as("%s must be measured and agree with the broker", queue)
                    .isEqualTo((double) brokerDepth(queue));
        }
        assertThat(registry.get(RabbitQueueMetrics.PREFIX + ".consumers")
                .tag("queue", DLQ).gauge().value())
                .as("consumer count is reported for the dead-letter queue too")
                .isNotNull();
    }

    /** Publish through the default exchange, which is how the dead-letter route delivers. */
    private void publishToDlq(int count) {
        for (int i = 0; i < count; i++) {
            rabbitTemplate.send("", DLQ, new Message(new byte[0], new MessageProperties()));
        }
    }

    private int brokerDepth(String queue) {
        var info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private double gaugeDepth(String queue) {
        return registry.get(RabbitQueueMetrics.PREFIX + ".depth")
                .tag("queue", queue).gauge().value();
    }

    private static boolean brokerIsListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5672), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
