package com.ems.notification_service.leave;

import com.ems.common.event.EventEnvelope;
import com.ems.common.messaging.QueueFactory;
import com.ems.common.outbox.IdempotentConsumer;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Independent leave consumer: SMTP is never attempted on the broker thread. */
@Component
public class LeaveNotificationConsumer {
    private final JdbcTemplate jdbc;

    public LeaveNotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
    static class Topology {
        @Bean
        Declarables leaveNotificationTopology() {
            return QueueFactory.declare("notification.leave", List.of("leave.approved", "user.verified"));
        }

        @Bean
        Listener leaveNotificationListener(LeaveNotificationConsumer consumer) {
            return new Listener(consumer);
        }
    }

    static class Listener {
        private final LeaveNotificationConsumer consumer;

        Listener(LeaveNotificationConsumer consumer) {
            this.consumer = consumer;
        }

        @RabbitListener(queues = "notification.leave.q")
        public void receive(EventEnvelope<JsonNode> event) {
            consumer.accept(event);
        }
    }

    @Transactional
    @IdempotentConsumer("notification.leave")
    public void accept(EventEnvelope<JsonNode> event) {
        var p = event.payload();
        switch (event.type()) {
            case "user.verified" -> {
                long employee = p.path("userId").asLong();
                String email = p.path("email").asText();
                if (employee <= 0 || email == null || !email.contains("@") || email.length() > 320)
                    throw new IllegalArgumentException("Invalid leave recipient");
                Instant at = Instant.parse(p.path("verifiedAt").asText());
                jdbc.update(
                        "INSERT INTO leave_notification_contact(employee_id,email,as_of) VALUES (?,?,?) ON CONFLICT(employee_id) DO UPDATE SET email=excluded.email,as_of=excluded.as_of WHERE leave_notification_contact.as_of<excluded.as_of",
                        employee,
                        email,
                        Timestamp.from(at));
            }
            case "leave.approved" -> {
                long employee = p.path("employeeId").asLong();
                if (employee <= 0) throw new IllegalArgumentException("Invalid leave employee");
                jdbc.update(
                        "INSERT INTO leave_notification_delivery(workflow_id,event_id,employee_id,request_id,start_date,end_date) VALUES (?,?,?,?,?,?) ON CONFLICT(workflow_id) DO NOTHING",
                        UUID.fromString(p.path("workflowId").asText()),
                        event.eventId(),
                        employee,
                        UUID.fromString(p.path("requestId").asText()),
                        Date.valueOf(p.path("startDate").asText()),
                        Date.valueOf(p.path("endDate").asText()));
            }
            default -> {}
        }
    }
}
