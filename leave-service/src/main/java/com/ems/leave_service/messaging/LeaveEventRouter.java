package com.ems.leave_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.leave_service.projection.EmployeeProjectionHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class LeaveEventRouter {
    private final EmployeeProjectionHandler handler;

    public LeaveEventRouter(EmployeeProjectionHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = MessagingConfig.WORK_QUEUE)
    public void onEvent(EventEnvelope<JsonNode> event) {
        switch (event.type()) {
            case "user.verified", "user.assignment.updated", "employee.profile.updated" -> handler.apply(event);
            default -> {
                /* Shared retry delivery still dispatches by envelope type. */
            }
        }
    }
}
