package com.ems.notification_service.timesheet;

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

/** Independent timesheet consumer: SMTP is never attempted on the broker thread. */
@Component
public class TimesheetNotificationConsumer {
    private final JdbcTemplate jdbc;

    public TimesheetNotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
    static class Topology {
        @Bean
        Declarables timesheetNotificationTopology() {
            return QueueFactory.declare("notification.timesheet", List.of("timesheet.decided", "user.verified"));
        }

        @Bean
        Listener timesheetNotificationListener(TimesheetNotificationConsumer consumer) {
            return new Listener(consumer);
        }
    }

    static class Listener {
        private final TimesheetNotificationConsumer consumer;

        Listener(TimesheetNotificationConsumer consumer) {
            this.consumer = consumer;
        }

        @RabbitListener(queues = "notification.timesheet.q")
        public void receive(EventEnvelope<JsonNode> event) {
            consumer.accept(event);
        }
    }

    @Transactional
    @IdempotentConsumer("notification.timesheet")
    public void accept(EventEnvelope<JsonNode> event) {
        var p = event.payload();
        switch (event.type()) {
            case "user.verified" -> {
                long employee = p.path("userId").asLong();
                String email = p.path("email").asText();
                if (employee <= 0 || email == null || !email.contains("@") || email.length() > 320)
                    throw new IllegalArgumentException("Invalid timesheet recipient");
                Instant at = Instant.parse(p.path("verifiedAt").asText());
                jdbc.update(
                        "INSERT INTO timesheet_notification_contact(employee_id,email,as_of) VALUES (?,?,?) ON CONFLICT(employee_id) DO UPDATE SET email=excluded.email,as_of=excluded.as_of WHERE timesheet_notification_contact.as_of<excluded.as_of",
                        employee,
                        email,
                        Timestamp.from(at));
            }
            case "timesheet.decided" -> {
                long employee = p.path("employeeId").asLong();
                String outcome = p.path("outcome").asText();
                if (employee <= 0 || !Set.of("APPROVED", "REJECTED").contains(outcome))
                    throw new IllegalArgumentException("Invalid timesheet decision");
                String reason = p.path("reason").asText("");
                if (reason.length() > 500 || outcome.equals("REJECTED") && reason.isBlank())
                    throw new IllegalArgumentException("Invalid decision reason");
                jdbc.update(
                        "INSERT INTO timesheet_notification_delivery(decision_id,event_id,employee_id,sheet_id,week_start,outcome,reason) VALUES (?,?,?,?,?,?,?) ON CONFLICT(decision_id) DO NOTHING",
                        UUID.fromString(p.path("decisionId").asText()),
                        event.eventId(),
                        employee,
                        UUID.fromString(p.path("sheetId").asText()),
                        Date.valueOf(p.path("weekStart").asText()),
                        outcome,
                        reason);
            }
            default -> {}
        }
    }
}
