package com.ems.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.ems.common.web.CorrelationIdFilter;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Proves the topology against a real broker: that the exchanges and the three per-service
 * queues are declared on startup, and that a listener which always throws gets exactly
 * {@link RabbitTopologyConfig#MAX_ATTEMPTS} attempts before the message is parked.
 */
@SpringBootTest(classes = RabbitTopologyIntegrationTest.TestApp.class)
@Testcontainers
class RabbitTopologyIntegrationTest {

    private static final String SERVICE = "topologytest";
    private static final String WORK_QUEUE = "topologytest.q";
    private static final String PATTERN = "topologytest.event.*";
    private static final String ROUTING_KEY = "topologytest.event.created";
    private static final String CORRELATION_ID = "corr-42";

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private AlwaysFailingListener listener;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void declaresTheSharedExchangesAndThePerServiceQueues() {
        // A passive declare is how you ask the broker whether an exchange is already there.
        rabbitTemplate.execute(channel -> {
            channel.exchangeDeclarePassive(RabbitTopologyConfig.EVENTS_EXCHANGE);
            channel.exchangeDeclarePassive(RabbitTopologyConfig.DEAD_LETTER_EXCHANGE);
            return null;
        });

        assertThat(rabbitAdmin.getQueueProperties(QueueFactory.workQueue(SERVICE)))
                .isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(QueueFactory.retryQueue(SERVICE)))
                .isNotNull();
        assertThat(rabbitAdmin.getQueueProperties(QueueFactory.parkedQueue(SERVICE)))
                .isNotNull();
    }

    @Test
    void parksAMessageThatKeepsFailingAfterExactlyThreeAttempts() {
        MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, CORRELATION_ID);
        rabbitTemplate.convertAndSend(RabbitTopologyConfig.EVENTS_EXCHANGE, ROUTING_KEY, new TestPayload("payroll"));

        Message parked = receiveParked();
        assertThat(parked).as("message should end up on the parked queue").isNotNull();

        // Read after the message is parked: at that point the interceptor is done, so the
        // count cannot still be moving. A fourth delivery would have to come from a
        // requeue, which would show up here as well.
        assertThat(listener.attempts).hasValue(RabbitTopologyConfig.MAX_ATTEMPTS);

        assertThat(parked.getMessageProperties().getHeaders())
                .containsEntry(CorrelationIdFilter.CORRELATION_ID_HEADER, CORRELATION_ID)
                .containsEntry(RepublishMessageRecoverer.X_ORIGINAL_ROUTING_KEY, ROUTING_KEY)
                .containsKey(RepublishMessageRecoverer.X_EXCEPTION_MESSAGE);

        assertThat(listener.correlationIds)
                .as("the inbound post processor should restore the publisher's correlation id")
                .containsOnly(CORRELATION_ID);
    }

    private Message receiveParked() {
        return Awaitility.await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(250))
                .until(() -> rabbitTemplate.receive(QueueFactory.parkedQueue(SERVICE)), message -> message != null);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        Declarables testTopology() {
            return QueueFactory.declare(SERVICE, List.of(PATTERN));
        }

        @Bean
        AlwaysFailingListener alwaysFailingListener() {
            return new AlwaysFailingListener();
        }
    }

    static class AlwaysFailingListener {

        private final AtomicInteger attempts = new AtomicInteger();
        private final List<String> correlationIds = Collections.synchronizedList(new ArrayList<>());

        @RabbitListener(queues = WORK_QUEUE)
        void onEvent(TestPayload payload) {
            correlationIds.add(MDC.get(CorrelationIdFilter.CORRELATION_ID_MDC_KEY));
            attempts.incrementAndGet();
            throw new IllegalStateException("this listener never succeeds");
        }
    }

    record TestPayload(String name) {}
}
