package com.securetravels.crm.communications;

import com.securetravels.crm.common.config.AppProperties;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for outbound WhatsApp (Module 4, ADR 0005).
 *
 * <p>Declared <strong>only</strong> in {@code BROKER} mode. In the default
 * {@code INLINE} mode not one of these beans exists, so the application has no
 * connection, no declaration attempt, and no dependency on a running broker —
 * which is what keeps the existing test suite green with nothing installed.
 *
 * <pre>
 *   securetravels.communication (direct)
 *        │ whatsapp.dispatch
 *        ▼
 *   securetravels.whatsapp.dispatch ──nack/expired──▶ (default exchange)
 *        │                                                │
 *        ▼                                                ▼
 *   one @RabbitListener                    securetravels.whatsapp.dispatch.dlq
 * </pre>
 *
 * <p>The dead-letter route uses the broker's native DLX with the <em>default</em>
 * exchange as the DLX target, so the DLQ needs no binding of its own and no
 * second exchange to keep in sync.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.messaging", name = "mode", havingValue = "BROKER")
public class CommunicationMessagingConfig {

    @Bean
    public DirectExchange communicationExchange(AppProperties props) {
        return new DirectExchange(props.getMessaging().getExchange(), true, false);
    }

    @Bean
    public Queue whatsappDispatchQueue(AppProperties props) {
        return QueueBuilder.durable(props.getMessaging().getQueue())
                .deadLetterExchange("")                                    // default exchange
                .deadLetterRoutingKey(props.getMessaging().getDeadLetterQueue())
                .build();
    }

    @Bean
    public Queue whatsappDeadLetterQueue(AppProperties props) {
        return QueueBuilder.durable(props.getMessaging().getDeadLetterQueue()).build();
    }

    @Bean
    public Binding whatsappDispatchBinding(DirectExchange communicationExchange,
                                           Queue whatsappDispatchQueue,
                                           AppProperties props) {
        return BindingBuilder.bind(whatsappDispatchQueue)
                .to(communicationExchange)
                .with(props.getMessaging().getRoutingKey());
    }

    /**
     * Container factory for {@link WhatsAppDispatchListener}.
     *
     * <p>Two settings carry the whole failure policy:
     *
     * <ul>
     *   <li>{@code defaultRequeueRejected=false} — otherwise a rejected message
     *       is requeued forever and <strong>never reaches the DLQ</strong>. This
     *       is the single most common way a RabbitMQ DLQ silently does nothing.</li>
     *   <li>{@link RejectAndDontRequeueRecoverer} — when the retry budget is
     *       spent, reject (not requeue) so the message is dead-lettered.</li>
     * </ul>
     *
     * <p>Linear backoff matches the inline path's cadence, so switching
     * {@code MESSAGING_MODE} does not change what the customer experiences.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory whatsappListenerFactory(
            ConnectionFactory connectionFactory, AppProperties props) {
        long backoff = props.getMessaging().getRetryBackoffMillis();
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);   // serialise sends: Interakt's quota is per account
        factory.setPrefetchCount(1);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(props.getMessaging().getMaxAttempts())
                .backOffOptions(backoff, 1.0, backoff * props.getMessaging().getMaxAttempts())
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build());
        return factory;
    }
}
