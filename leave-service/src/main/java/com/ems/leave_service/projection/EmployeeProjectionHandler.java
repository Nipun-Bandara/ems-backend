package com.ems.leave_service.projection;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
public class EmployeeProjectionHandler {
    private final EmployeeProjectionStore store;

    public EmployeeProjectionHandler(EmployeeProjectionStore store) {
        this.store = store;
    }

    @IdempotentConsumer("leave.employee-projection")
    @Transactional
    public void apply(EventEnvelope<JsonNode> event) {
        Objects.requireNonNull(event.eventId(), "eventId is required");
        Objects.requireNonNull(event.occurredAt(), "occurredAt is required");
        JsonNode payload = Objects.requireNonNull(event.payload(), "payload is required");
        long userId = requiredId(payload, "userId");
        store.lockAndCreate(userId);
        switch (event.type()) {
            // The employee shell contract defines verified employees as ACTIVE.
            case "user.verified" -> store.status(userId, true, instant(payload, "verifiedAt"));
            case "user.assignment.updated" ->
                store.assignment(userId, nullableId(payload, "departmentId"), instant(payload, "assignedAt"));
            case "employee.profile.updated" -> {
                Long manager = nullableId(payload, "reportingManagerUserId");
                if (Objects.equals(manager, userId)) throw new IllegalArgumentException("Self reporting is invalid");
                store.profile(userId, manager, event.occurredAt());
                // departmentId in this snapshot can lag identity assignments. Never use it.
                // Current producers omit status; absence must not imply activation.
            }
            default -> throw new IllegalArgumentException("Unsupported projection event type");
        }
    }

    private static long requiredId(JsonNode payload, String name) {
        Long id = nullableId(payload, name);
        if (id == null) throw new IllegalArgumentException(name + " is required");
        return id;
    }

    private static Long nullableId(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null) throw new IllegalArgumentException(name + " must be present (nullable)");
        if (value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() <= 0)
            throw new IllegalArgumentException(name + " must be a positive integer");
        return value.asLong();
    }

    private static Instant instant(JsonNode payload, String name) {
        JsonNode value = payload.get(name);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException(name + " is required");
        return Instant.parse(value.asText());
    }
}
