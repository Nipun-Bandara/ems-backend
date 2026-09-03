package com.ems.common.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

class QueueFactoryTest {

    private static final Declarables TOPOLOGY = QueueFactory.declare("billing", List.of("invoice.*", "payment.*"));

    @Test
    void declaresAWorkQueueThatDeadLettersToTheServicesRetryQueue() {
        Queue work = queue("billing.q");

        assertThat(work.isDurable()).isTrue();
        assertThat(work.getArguments())
                .containsEntry("x-dead-letter-exchange", RabbitTopologyConfig.DEAD_LETTER_EXCHANGE)
                // Scoped to this service: the exchange is shared, so keeping the event's own
                // routing key would push one service's failure into every other retry queue.
                .containsEntry("x-dead-letter-routing-key", "billing.retry");
    }

    @Test
    void declaresARetryQueueThatExpiresBackOntoTheEventsExchange() {
        Queue retry = queue("billing.retry");

        assertThat(retry.getArguments())
                .containsEntry("x-message-ttl", (int) QueueFactory.RETRY_DELAY_MS)
                .containsEntry("x-dead-letter-exchange", RabbitTopologyConfig.EVENTS_EXCHANGE)
                .containsEntry("x-dead-letter-routing-key", "billing.retry");
    }

    @Test
    void declaresAParkedQueueThatGoesNowhere() {
        Queue parked = queue("billing.parked");

        assertThat(parked.isDurable()).isTrue();
        assertThat(parked.getArguments()).isEmpty();
    }

    @Test
    void bindsTheWorkQueueToEveryPatternAndToItsOwnRetryKey() {
        assertThat(TOPOLOGY.getDeclarablesByType(Binding.class))
                .filteredOn(binding -> binding.getDestination().equals("billing.q"))
                .extracting(Binding::getExchange, Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        tuple(RabbitTopologyConfig.EVENTS_EXCHANGE, "invoice.*"),
                        tuple(RabbitTopologyConfig.EVENTS_EXCHANGE, "payment.*"),
                        // The way back in once the retry delay has expired.
                        tuple(RabbitTopologyConfig.EVENTS_EXCHANGE, "billing.retry"));
    }

    @Test
    void bindsTheFailureQueuesToTheDeadLetterExchange() {
        assertThat(TOPOLOGY.getDeclarablesByType(Binding.class))
                .filteredOn(binding -> binding.getExchange().equals(RabbitTopologyConfig.DEAD_LETTER_EXCHANGE))
                .extracting(Binding::getDestination, Binding::getRoutingKey)
                .containsExactlyInAnyOrder(
                        tuple("billing.retry", "billing.retry"), tuple("billing.parked", "billing.parked"));
    }

    @Test
    void derivesTheParkedRoutingKeyFromTheQueueTheMessageCameFrom() {
        assertThat(QueueFactory.parkedRoutingKeyFor("billing.q")).isEqualTo("billing.parked");
        // A listener on a queue this factory did not create still gets somewhere sensible.
        assertThat(QueueFactory.parkedRoutingKeyFor("legacy-queue")).isEqualTo("legacy-queue.parked");
    }

    @Test
    void rejectsATopologyItCannotName() {
        assertThatIllegalArgumentException().isThrownBy(() -> QueueFactory.declare(" ", List.of("invoice.*")));
        assertThatIllegalArgumentException().isThrownBy(() -> QueueFactory.declare("billing", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> QueueFactory.declare("billing", List.of(" ")));
    }

    private static Queue queue(String name) {
        return TOPOLOGY.getDeclarablesByType(Queue.class).stream()
                .filter(candidate -> candidate.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no queue named " + name));
    }
}
