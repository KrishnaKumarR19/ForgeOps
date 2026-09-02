package com.forgeops.events.infrastructure.messaging;

import com.forgeops.events.application.EventConsumerProperties;
import com.forgeops.events.application.NonRetryableEventProcessingException;
import java.util.Map;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.RetryPolicy;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;

/**
 * Wires the {@code @RabbitListener} container for the asynchronous event consumer (Phase 6
 * Slice 3, ADR-0014). Encodes the delivery/retry/dead-letter semantics that ADR-0014 fixes:
 *
 * <ul>
 *   <li><b>Explicit acknowledgement after successful processing (INV-MSG-004, FR-RL-11):</b>
 *       {@link AcknowledgeMode#AUTO} makes the container acknowledge only after the listener
 *       method returns normally, and reject (nack) if it throws. Because the listener applies
 *       and <em>commits</em> the DB effect before returning, the ack always follows the commit
 *       — never before. This is not auto-ack-on-delivery; the ack is bound to processing
 *       success.</li>
 *   <li><b>Bounded retry of transient failures (INV-MSG-005, FR-RL-4):</b> a stateless retry
 *       interceptor retries the listener up to {@code maxAttempts} with exponential backoff.
 *       {@link NonRetryableEventProcessingException} is <em>not</em> retried — a poison message
 *       is rejected on the first failure.</li>
 *   <li><b>Dead-letter on exhaustion (INV-MSG-006, FR-RL-5):</b> when retries are exhausted (or
 *       for a non-retryable failure) {@link RejectAndDontRequeueRecoverer} rejects the message
 *       without requeue; the broker then routes it to the processing queue's configured
 *       dead-letter exchange/queue. Combined with {@code defaultRequeueRejected=false} this
 *       guarantees a failing message neither loops forever nor is silently dropped.</li>
 * </ul>
 *
 * <p>The retry/backoff here is the <strong>consumer</strong> policy and is entirely separate
 * from the Slice 2 publisher backoff. Values are config-driven
 * ({@link EventConsumerProperties}) with safe deterministic defaults.
 */
@Configuration(proxyBeanMethods = false)
@EnableRabbit
@EnableConfigurationProperties(EventConsumerProperties.class)
class EventConsumerConfig {

    /** Named so the {@code @RabbitListener} references exactly this factory. */
    static final String LISTENER_CONTAINER_FACTORY = "eventsListenerContainerFactory";

    @Bean(LISTENER_CONTAINER_FACTORY)
    SimpleRabbitListenerContainerFactory eventsListenerContainerFactory(
            ConnectionFactory connectionFactory, EventConsumerProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        // AUTO: ack after the listener returns normally; reject on exception. The listener
        // commits the DB effect before returning, so the ack strictly follows the commit.
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        // A rejected message must NOT be requeued blindly; the retry interceptor's recoverer
        // decides the terminal action (reject-without-requeue -> broker dead-letters it).
        factory.setDefaultRequeueRejected(false);
        factory.setConcurrentConsumers(properties.concurrency());
        factory.setAdviceChain(retryInterceptor(properties));
        return factory;
    }

    private RetryOperationsInterceptor retryInterceptor(EventConsumerProperties properties) {
        EventConsumerProperties.Retry retry = properties.retry();

        ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
        backOff.setInitialInterval(retry.initialDelay().toMillis());
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(retry.maxDelay().toMillis());

        // Retry transient failures up to maxAttempts, but never retry a poison message.
        Map<Class<? extends Throwable>, Boolean> retryable = Map.of(
                NonRetryableEventProcessingException.class, Boolean.FALSE,
                Exception.class, Boolean.TRUE);
        // traverseCauses=true so a NonRetryable cause wrapped by the listener plumbing is still
        // recognized as non-retryable.
        RetryPolicy retryPolicy = new SimpleRetryPolicy(retry.maxAttempts(), retryable, true);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOff);

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                // On exhaustion (or a non-retryable failure): reject without requeue so the
                // broker dead-letters via the queue's x-dead-letter-exchange (INV-MSG-006).
                .recoverer(new RejectAndDontRequeueRecoverer())
                .build();
    }
}
