package com.ems.employee_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.employee_service.event.UserVerifiedPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class EmployeeEventRouter {

    private static final Logger log = LoggerFactory.getLogger(EmployeeEventRouter.class);

    private final EmployeeProfileHandler handler;

    public EmployeeEventRouter(EmployeeProfileHandler handler) {
        this.handler = handler;
    }

    @RabbitListener(queues = MessagingConfig.WORK_QUEUE)
    public void onEvent(EventEnvelope<JsonNode> event) {
        switch (event.type()) {
            case UserVerifiedPayload.TYPE -> handler.onUserVerified(event);
            // Registration (including verification resends) does not create a profile.
            // Do not deserialize or log its verification token.
            case MessagingConfig.USER_REGISTERED -> {}
            default ->
                log.debug("No employee handler is defined for {}; acknowledging {}", event.type(), event.eventId());
        }
    }
}
