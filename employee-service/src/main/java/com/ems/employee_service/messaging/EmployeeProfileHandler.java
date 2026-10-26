package com.ems.employee_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.employee_service.event.UserVerifiedPayload;
import com.ems.employee_service.repository.EmployeeProfileRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class EmployeeProfileHandler {

    private final EmployeeProfileRepository profiles;
    private final JsonMapper jsonMapper;

    public EmployeeProfileHandler(EmployeeProfileRepository profiles, JsonMapper jsonMapper) {
        this.profiles = profiles;
        this.jsonMapper = jsonMapper;
    }

    @IdempotentConsumer("employee.user-verified")
    @Transactional
    public void onUserVerified(EventEnvelope<JsonNode> event) {
        UserVerifiedPayload payload = jsonMapper.treeToValue(event.payload(), UserVerifiedPayload.class);
        profiles.createShell(payload.userId(), payload.email(), payload.username(), payload.verifiedAt());
    }
}
