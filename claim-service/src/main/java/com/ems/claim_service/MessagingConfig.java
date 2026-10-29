package com.ems.claim_service;

import com.ems.common.event.EventEnvelope;
import com.ems.common.messaging.QueueFactory;
import java.util.List;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import tools.jackson.databind.JsonNode;

@Configuration
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class MessagingConfig {
    private final ProjectionHandler handler;

    public MessagingConfig(ProjectionHandler handler) {
        this.handler = handler;
    }

    @Bean
    public Declarables claimTopology() {
        return QueueFactory.declare(
                "claim", List.of("user.verified", "user.assignment.updated", "employee.profile.updated"));
    }

    @RabbitListener(queues = "claim.q")
    public void event(EventEnvelope<JsonNode> event) {
        handler.apply(event);
    }
}
