package com.ems.org_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class MessagingConfigTest {

    @Test
    void declaresOrgQueueWithoutDomainEventPatterns() {
        var topology = new MessagingConfig().orgTopology();

        assertThat(topology.getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .contains("org.q");
        assertThat(topology.getDeclarablesByType(Binding.class))
                .filteredOn(binding -> binding.getDestination().equals("org.q"))
                .extracting(Binding::getExchange, Binding::getRoutingKey)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ems.events", "org.retry"));
    }
}
