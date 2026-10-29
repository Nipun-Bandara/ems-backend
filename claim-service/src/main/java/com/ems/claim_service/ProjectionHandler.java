package com.ems.claim_service;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import java.time.Instant;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
public class ProjectionHandler {
    private final EmployeeScopeStore scope;

    public ProjectionHandler(EmployeeScopeStore scope) {
        this.scope = scope;
    }

    @Transactional
    @IdempotentConsumer("claim.projections")
    public void apply(EventEnvelope<JsonNode> event) {
        Objects.requireNonNull(event.eventId());
        var at = Objects.requireNonNull(event.occurredAt());
        var p = Objects.requireNonNull(event.payload());
        long user = id(p, "userId");
        scope.lock(true);
        switch (event.type()) {
            case "user.verified" ->
                scope.active(user, true, Instant.parse(p.path("verifiedAt").asText()));
            case "user.assignment.updated" ->
                scope.assignment(
                        user,
                        nullable(p, "departmentId"),
                        Instant.parse(p.path("assignedAt").asText()));
            case "employee.profile.updated" -> scope.profile(user, nullable(p, "reportingManagerUserId"), at);
            default -> throw new IllegalArgumentException("Unsupported event type");
        }
    }

    private long id(JsonNode p, String key) {
        var n = p.get(key);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() <= 0)
            throw new IllegalArgumentException("Positive " + key + " required");
        return n.asLong();
    }

    private Long nullable(JsonNode p, String key) {
        var n = p.get(key);
        if (n == null) throw new IllegalArgumentException(key + " must be present (nullable)");
        return n.isNull() ? null : id(p, key);
    }
}
