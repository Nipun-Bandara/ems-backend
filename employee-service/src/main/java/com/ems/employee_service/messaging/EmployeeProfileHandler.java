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
    private final jakarta.validation.Validator validator;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public EmployeeProfileHandler(
            EmployeeProfileRepository profiles,
            JsonMapper jsonMapper,
            jakarta.validation.Validator validator,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.validator = validator;
        this.profiles = profiles;
        this.jsonMapper = jsonMapper;
    }

    @IdempotentConsumer("employee.user-verified")
    @Transactional
    public void onUserVerified(EventEnvelope<JsonNode> event) {
        UserVerifiedPayload payload = jsonMapper.treeToValue(event.payload(), UserVerifiedPayload.class);
        lockUser(payload.userId());
        profiles.createShell(payload.userId(), payload.email(), payload.username(), payload.verifiedAt());
        profiles.refreshDepartment(payload.userId());
    }

    @IdempotentConsumer("employee.user-assignment-updated")
    @Transactional
    public void onAssignmentUpdated(EventEnvelope<JsonNode> event) {
        var payload = jsonMapper.treeToValue(
                event.payload(), com.ems.employee_service.event.UserAssignmentUpdatedPayload.class);
        var errors = validator.validate(payload);
        if (!errors.isEmpty()) throw new jakarta.validation.ConstraintViolationException(errors);
        lockUser(payload.userId());
        profiles.replicateAssignment(payload.userId(), payload.departmentId(), payload.assignedAt());
        profiles.refreshDepartment(payload.userId());
    }

    // Serialize verification and assignment for a user, including before a shell exists.
    private void lockUser(Long userId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, userId);
    }
}
