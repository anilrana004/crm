package com.securetravels.crm.communications;

import com.securetravels.crm.BaseIT;
import com.securetravels.crm.customer.Customer360;
import com.securetravels.crm.customer.Customer360Repository;
import com.securetravels.crm.user.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Module 4 in {@code BROKER} mode, against a real RabbitMQ (ADR 0005).
 *
 * <p>This is the only test that needs a broker, and it skips itself when one is
 * not listening so the suite still runs on a clean machine. What it proves is
 * the part that is invisible until it misbehaves in production:
 *
 * <ul>
 *   <li>the durable topology is actually declared;</li>
 *   <li>a queued message is consumed and delivered rather than being published
 *       into a queue nobody is listening to;</li>
 *   <li>the container's retry advice runs and an exhausted message is
 *       <strong>rejected</strong> so it reaches the dead-letter queue — the
 *       {@code defaultRequeueRejected=false} detail, without which a DLQ quietly
 *       receives nothing and messages loop forever instead.</li>
 * </ul>
 *
 * <p>The gateway is mocked rather than pointed at a live account, so the send
 * outcome is deterministic and no Interakt quota is consumed. The DB row and the
 * broker state are still real.
 */
@TestPropertySource(properties = {
        "app.messaging.mode=BROKER",
        "app.messaging.max-attempts=3",
        // Short backoff: the retry policy itself is not what is under test.
        "app.messaging.retry-backoff-millis=100",
        // Keep the webhook secret consistent with the other Module 4 tests.
        "app.whatsapp.mode=SANDBOX"
})
@Import(WhatsAppBrokerIT.QuietContext.class)
class WhatsAppBrokerIT extends BaseIT {

    private static final String EXCHANGE = "securetravels.communication";
    private static final String QUEUE = "securetravels.whatsapp.dispatch";
    private static final String DLQ = "securetravels.whatsapp.dispatch.dlq";
    private static final String ROUTING_KEY = "whatsapp.dispatch";

    /** Replaces the conditional gateway bean so the send outcome is deterministic. */
    @MockitoBean private WhatsAppGateway gateway;

    @Autowired private RabbitAdmin rabbitAdmin;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private WhatsAppMessageRepository messages;
    @Autowired private Customer360Repository customerRepository;

    @TestConfiguration
    static class QuietContext {
    }

    @BeforeEach
    void requireBroker() {
        assumeTrue(brokerIsListening(), "RabbitMQ is not listening on 127.0.0.1:5672; skipping broker tests");
        // The DLQ is a real queue with real state; start each test from empty so
        // the assertions below are about this test's message.
        purge(QUEUE);
        purge(DLQ);
        when(gateway.provider()).thenReturn("INTERAKT");
    }

    // ------------------------------------------------------------------ topology

    @Test
    @DisplayName("the durable exchange, dispatch queue, and dead-letter queue all exist")
    void topologyIsDeclared() {
        // RabbitAdmin can read queue state but has no exchange read API, so the
        // exchange is proven with a passive declare: it throws unless the exchange
        // already exists with a matching type.
        Boolean exchangeExists = rabbit.execute(channel -> {
            channel.exchangeDeclarePassive(EXCHANGE);
            return Boolean.TRUE;
        });
        assertThat(exchangeExists).isTrue();

        QueueInformation dispatch = rabbitAdmin.getQueueInfo(QUEUE);
        assertThat(dispatch).isNotNull();
        assertThat(dispatch.getName()).isEqualTo(QUEUE);
        // A consumer is attached, so a published message is actually picked up
        // rather than accumulating in a queue nobody is reading.
        assertThat(dispatch.getConsumerCount()).isEqualTo(1);
        assertThat(rabbitAdmin.getQueueInfo(DLQ)).isNotNull();

        // The dead-letter *arguments* are deliberately not asserted here:
        // RabbitAdmin's queue properties expose only management keys, not the
        // x-dead-letter-* arguments, and asserting them by re-declaring would
        // just restate our own configuration. The wiring is proved where it
        // matters — behaviourally — by exhaustedMessageIsDeadLettered and
        // malformedPayloadIsDeadLettered, which watch a message actually arrive.
    }

    // ------------------------------------------------------------------ happy path

    @Test
    @DisplayName("a queued message is consumed off the broker and delivered")
    void messageIsConsumedAndDelivered() throws Exception {
        when(gateway.send(any())).thenReturn(WhatsAppGateway.SendResult.ok("interakt-live-1"));

        String token = loginAsSales();
        String customerId = newCustomer();
        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId)))
                .andExpect(status().isAccepted());

        // Delivery happens on a consumer thread, so poll rather than sleep blindly.
        awaitUntil("message is delivered", () ->
                messages.findAll().get(0).getStatus() == WhatsAppMessage.Status.SENT, 20_000);

        assertThat(messages.findAll().get(0).getProviderMessageId()).isEqualTo("interakt-live-1");
        // The queue drained: the message was consumed, not just published.
        awaitUntil("queue drains", () -> messageCount(QUEUE) == 0, 10_000);
    }

    // ------------------------------------------------------------------ dead letter

    @Test
    @DisplayName("a message that keeps failing is rejected into the dead-letter queue")
    void exhaustedMessageIsDeadLettered() throws Exception {
        // Retryable every time, so only the container's budget can stop it.
        when(gateway.send(any())).thenReturn(
                WhatsAppGateway.SendResult.failure("Connection refused", null, "connect ECONNREFUSED", true));

        String token = loginAsSales();
        String customerId = newCustomer();
        mockMvc.perform(post("/api/whatsapp/send")
                        .header("Authorization", authHeader(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody(customerId)))
                .andExpect(status().isAccepted());

        awaitUntil("message reaches the DLQ", () -> messageCount(DLQ) == 1, 30_000);

        // The retry advice really did run, rather than the first failure being
        // dead-lettered immediately.
        assertThat(messages.findAll().get(0).getAttempts()).isGreaterThanOrEqualTo(3);
        // In BROKER mode the container owns the budget, so the row stays QUEUED:
        // it is not the sender's job to declare exhaustion, or two counters race
        // over the same row. The DLQ is the artifact of record.
        assertThat(messages.findAll().get(0).getStatus()).isEqualTo(WhatsAppMessage.Status.QUEUED);
        awaitUntil("dispatch queue drains", () -> messageCount(QUEUE) == 0, 10_000);
    }

    @Test
    @DisplayName("an unparseable payload is dead-lettered rather than looping forever")
    void malformedPayloadIsDeadLettered() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_TEXT_PLAIN);
        rabbit.send(EXCHANGE, ROUTING_KEY,
                new Message("not-a-uuid".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties));

        // Requeueing an unparseable payload can never succeed, so it must end up
        // where an operator can see and discard it.
        awaitUntil("malformed payload reaches the DLQ", () -> messageCount(DLQ) == 1, 30_000);
    }

    // ------------------------------------------------------------------ helpers

    /** Ready (unacknowledged) messages currently sitting in a queue. */
    private int messageCount(String queue) {
        QueueInformation info = rabbitAdmin.getQueueInfo(queue);
        return info == null ? 0 : info.getMessageCount();
    }

    private void purge(String queue) {
        rabbitAdmin.purgeQueue(queue, false);
    }

    private static boolean brokerIsListening() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 5672), 1500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Poll until the condition holds, failing with the description if it never does. */
    private static void awaitUntil(String what, BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private String loginAsSales() throws Exception {
        createUser("broker@securetravels.in", "Broker Rep", Role.SALES, "sales123");
        return login("broker@securetravels.in", "sales123");
    }

    private String newCustomer() {
        return customerRepository.save(Customer360.fromLead("Asha Rao", "9876500000", "9876500000", "9876500000",
                "asha.broker@example.com", true, "broker IT")).getId().toString();
    }

    private String sendBody(String customerId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjectType", "CUSTOMER");
        body.put("subjectId", customerId);
        body.put("templateCode", "BOOKING_CONFIRMED");
        body.put("mobile", "9876500000");
        body.put("bodyValues", List.of("Asha Rao", "TOH-2026-0001", "Manali", "2026-11-02"));
        return objectMapper.writeValueAsString(body);
    }
}
