package com.ems.common.messaging;

import static com.ems.common.messaging.RabbitTopologyConfig.DEAD_LETTER_EXCHANGE;
import static com.ems.common.messaging.RabbitTopologyConfig.EVENTS_EXCHANGE;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.util.Assert;

/**
 * Builds the three queues a service needs to consume events safely, so that every service
 * ends up with the same failure handling instead of its own.
 *
 * <p>A service declares its own corner of the topology by exposing the result as a bean,
 * which {@code RabbitAdmin} then declares on startup:
 *
 * <pre>{@code
 * @Bean
 * Declarables identityTopology() {
 *     return QueueFactory.declare("identity", List.of("user.*", "department.*"));
 * }
 * }</pre>
 *
 * <p>For a service named {@code identity} that produces:
 *
 * <ul>
 *   <li>{@code identity.q} — the work queue, bound to {@value RabbitTopologyConfig#EVENTS_EXCHANGE}
 *       on each pattern, dead-lettering to {@value RabbitTopologyConfig#DEAD_LETTER_EXCHANGE}.
 *   <li>{@code identity.retry} — holds a dead-lettered message for {@value #RETRY_DELAY_MS}ms
 *       and then dead-letters it back to {@value RabbitTopologyConfig#EVENTS_EXCHANGE}, where it
 *       reaches {@code identity.q} again. A delay loop, not a counter: the attempt limit lives in
 *       {@link RabbitTopologyConfig#emsRetryInterceptor}.
 *   <li>{@code identity.parked} — terminal. Messages that exhausted their attempts wait here for
 *       a human, with the failure recorded in the {@code x-exception-*} headers.
 * </ul>
 *
 * <p>Both failure queues are addressed by service-scoped routing keys ({@code identity.retry},
 * {@code identity.parked}) rather than by the event's own routing key. The exchanges are shared,
 * so keeping the original key would put one service's failure into every other subscriber's
 * retry queue. The original key is still on the message, in the {@code x-death} headers.
 */
public final class QueueFactory {

    /** How long a dead-lettered message waits in {@code .retry} before it is offered again. */
    public static final long RETRY_DELAY_MS = 10_000L;

    private static final String WORK_SUFFIX = ".q";
    private static final String RETRY_SUFFIX = ".retry";
    private static final String PARKED_SUFFIX = ".parked";

    private QueueFactory() {}

    /** Name of the queue {@code serviceName} consumes events from. */
    public static String workQueue(String serviceName) {
        return requireServiceName(serviceName) + WORK_SUFFIX;
    }

    /** Name of the delay queue a failed delivery for {@code serviceName} waits in. */
    public static String retryQueue(String serviceName) {
        return requireServiceName(serviceName) + RETRY_SUFFIX;
    }

    /** Name of the queue holding messages {@code serviceName} could not handle. */
    public static String parkedQueue(String serviceName) {
        return requireServiceName(serviceName) + PARKED_SUFFIX;
    }

    /**
     * Declares the work, retry and parked queues for {@code serviceName} and binds the work
     * queue to {@value RabbitTopologyConfig#EVENTS_EXCHANGE} on every pattern given.
     *
     * @param serviceName short name of the service, used as the queue name prefix
     * @param patterns topic patterns the service subscribes to, for example {@code "user.*"}
     */
    public static Declarables declare(String serviceName, List<String> patterns) {
        requireServiceName(serviceName);
        Assert.notEmpty(patterns, "patterns must not be empty");

        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange deadLetter = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);

        Queue work = QueueBuilder.durable(workQueue(serviceName))
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(retryQueue(serviceName))
                .build();
        Queue retry = QueueBuilder.durable(retryQueue(serviceName))
                .ttl((int) RETRY_DELAY_MS)
                .deadLetterExchange(EVENTS_EXCHANGE)
                .deadLetterRoutingKey(retryQueue(serviceName))
                .build();
        Queue parked = QueueBuilder.durable(parkedQueue(serviceName)).build();

        List<Declarable> declarables = new ArrayList<>(List.of(work, retry, parked));
        for (String pattern : patterns) {
            Assert.hasText(pattern, "pattern must not be blank");
            declarables.add(BindingBuilder.bind(work).to(events).with(pattern));
        }
        // The way back in after the retry delay, and the two failure paths off the DLX.
        declarables.add(BindingBuilder.bind(work).to(events).with(retryQueue(serviceName)));
        declarables.add(BindingBuilder.bind(retry).to(deadLetter).with(retryQueue(serviceName)));
        declarables.add(BindingBuilder.bind(parked).to(deadLetter).with(parkedQueue(serviceName)));

        return new Declarables(declarables.toArray(new Declarable[0]));
    }

    /**
     * Routing key that parks a message a listener could not handle, derived from the queue it
     * was consumed from so that the recoverer does not need to be told which service it is in.
     *
     * <p>Public because {@link RabbitTopologyConfig} reaches it through a SpEL expression.
     */
    public static String parkedRoutingKeyFor(String consumerQueue) {
        Assert.hasText(consumerQueue, "consumerQueue must not be blank");
        return consumerQueue.endsWith(WORK_SUFFIX)
                ? consumerQueue.substring(0, consumerQueue.length() - WORK_SUFFIX.length()) + PARKED_SUFFIX
                : consumerQueue + PARKED_SUFFIX;
    }

    private static String requireServiceName(String serviceName) {
        Assert.hasText(serviceName, "serviceName must not be blank");
        return serviceName;
    }
}
