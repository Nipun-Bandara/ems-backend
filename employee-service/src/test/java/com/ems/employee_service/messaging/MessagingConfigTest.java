package com.ems.employee_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class MessagingConfigTest {

    @Test
    void declaresEmployeeQueuesAndBothUserEventBindings() {
        var topology = new MessagingConfig().employeeTopology();
        assertThat(topology.getDeclarablesByType(Queue.class))
                .extracting(Queue::getName)
                .containsExactly("employee.q", "employee.retry", "employee.parked");
        assertThat(topology.getDeclarablesByType(Binding.class))
                .filteredOn(binding -> binding.getDestination().equals("employee.q"))
                .extracting(Binding::getExchange, Binding::getRoutingKey)
                .containsExactly(
                        tuple("ems.events", "user.verified"),
                        tuple("ems.events", "user.registered"),
                        tuple("ems.events", "user.assignment.updated"),
                        tuple("ems.events", "employee.retry"));
    }
}
