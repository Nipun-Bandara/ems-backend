package com.ems.notification_service.claim;

import com.ems.common.event.EventEnvelope;
import com.ems.common.messaging.QueueFactory;
import com.ems.common.outbox.IdempotentConsumer;
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

/** Independent claim consumer: SMTP is never attempted on the broker thread. */
@Component
public class ClaimNotificationConsumer {
    private final JdbcTemplate jdbc;

    public ClaimNotificationConsumer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
    static class Topology {
        @Bean
        Declarables claimNotificationTopology() {
            return QueueFactory.declare(
                    "notification.claim",
                    List.of("claim.submitted", "claim.approved", "claim.rejected", "user.verified"));
        }

        @Bean
        Listener claimNotificationListener(ClaimNotificationConsumer consumer) {
            return new Listener(consumer);
        }
    }

    static class Listener {
        private final ClaimNotificationConsumer consumer;

        Listener(ClaimNotificationConsumer consumer) {
            this.consumer = consumer;
        }

        @RabbitListener(queues = "notification.claim.q")
        public void receive(EventEnvelope<JsonNode> event) {
            consumer.accept(event);
        }
    }

    @Transactional
    @IdempotentConsumer("notification.claim")
    public void accept(EventEnvelope<JsonNode> event) {
        var p = event.payload();
        switch (event.type()) {
            case "user.verified" -> {
                long employee = p.path("userId").asLong();
                String email = p.path("email").asText();
                if (employee <= 0 || email == null || !email.contains("@") || email.length() > 320)
                    throw new IllegalArgumentException("Invalid claim recipient");
                Instant at = Instant.parse(p.path("verifiedAt").asText());
                jdbc.update(
                        "INSERT INTO claim_notification_contact(employee_id,email,as_of) VALUES (?,?,?) ON CONFLICT(employee_id) DO UPDATE SET email=excluded.email,as_of=excluded.as_of WHERE claim_notification_contact.as_of<excluded.as_of",
                        employee,
                        email,
                        Timestamp.from(at));
            }
            case "claim.submitted", "claim.approved", "claim.rejected" -> {
                long employee = p.path("employeeId").asLong();
                String outcome = event.type().substring("claim.".length()).toUpperCase(Locale.ROOT);
                if (employee <= 0 || !outcome.equals(p.path("status").asText()))
                    throw new IllegalArgumentException("Invalid claim notification");
                String reason = "";
                for (var history : p.path("history")) {
                    if (outcome.equals(history.path("action").asText()))
                        reason = history.path("reason").asText("");
                }
                if (reason.length() > 500 || outcome.equals("REJECTED") && reason.isBlank())
                    throw new IllegalArgumentException("Invalid claim reason");
                var total = new java.math.BigDecimal(p.path("total").asText());
                String currency = p.path("currency").asText();
                if (total.signum() <= 0 || !currency.matches("[A-Z]{3}"))
                    throw new IllegalArgumentException("Invalid claim total/currency");
                jdbc.update(
                        "INSERT INTO claim_notification_delivery(delivery_id,event_id,employee_id,claim_id,total,currency,outcome,reason) VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(claim_id,outcome) DO NOTHING",
                        UUID.randomUUID(),
                        event.eventId(),
                        employee,
                        UUID.fromString(p.path("id").asText()),
                        total,
                        currency,
                        outcome,
                        reason);
            }
            default -> {}
        }
    }
}
