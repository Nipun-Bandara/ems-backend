package com.ems.identity_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.identity_service.event.DepartmentCreatedPayload;
import com.ems.identity_service.event.DepartmentDeletedPayload;
import com.ems.identity_service.event.DepartmentRenamedPayload;
import com.ems.identity_service.event.UserRegisteredPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@ConditionalOnProperty(name = "ems.messaging.enabled", havingValue = "true", matchIfMissing = true)
public class IdentityEventRouter {

    private static final Logger log = LoggerFactory.getLogger(IdentityEventRouter.class);

    private final UserRegisteredListener userRegisteredListener;
    private final DepartmentProjectionHandler departmentProjectionHandler;

    public IdentityEventRouter(
            UserRegisteredListener userRegisteredListener, DepartmentProjectionHandler departmentProjectionHandler) {
        this.userRegisteredListener = userRegisteredListener;
        this.departmentProjectionHandler = departmentProjectionHandler;
    }

    @RabbitListener(queues = MessagingConfig.WORK_QUEUE)
    public void onEvent(EventEnvelope<JsonNode> event) {
        switch (event.type()) {
            case UserRegisteredPayload.TYPE -> userRegisteredListener.onUserRegistered(event);
            case DepartmentCreatedPayload.TYPE -> departmentProjectionHandler.onCreated(event);
            case DepartmentRenamedPayload.TYPE -> departmentProjectionHandler.onRenamed(event);
            case DepartmentDeletedPayload.TYPE -> departmentProjectionHandler.onDeleted(event);
            default ->
                log.debug("No identity handler is defined for {}; acknowledging {}", event.type(), event.eventId());
        }
    }
}
