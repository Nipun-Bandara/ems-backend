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
    private final boolean commandsEnabled;
    private final EmployeeProjectionHandler handler;
    private final com.ems.leave_service.request.LeaveWorkflowService workflows;

    public LeaveEventRouter(
            EmployeeProjectionHandler handler,
            com.ems.leave_service.request.LeaveWorkflowService workflows,
            @org.springframework.beans.factory.annotation.Value("${ems.leave.workflow.commands-enabled:true}")
                    boolean commandsEnabled) {
        this.commandsEnabled = commandsEnabled;
        this.handler = handler;
        this.workflows = workflows;
    }

    @RabbitListener(queues = MessagingConfig.WORK_QUEUE)
    public void onEvent(EventEnvelope<JsonNode> event) {
        switch (event.type()) {
            case "leave.workflow.step" -> {
                if (commandsEnabled)
                    workflows.advance(
                            java.util.UUID.fromString(
                                    event.payload().path("workflowId").asText()),
                            java.util.UUID.fromString(
                                    event.payload().path("stepId").asText()));
            }
            case "user.verified", "user.assignment.updated", "employee.profile.updated" -> handler.apply(event);
            default -> {
                /* Shared retry delivery still dispatches by envelope type. */
            }
        }
    }
}
