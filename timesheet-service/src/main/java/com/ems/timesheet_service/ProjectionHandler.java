package com.ems.timesheet_service;

import static com.ems.timesheet_service.Models.*;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import java.time.*;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
public class ProjectionHandler {
    private final ProjectionStore store;
    private final OrganizationCalendar calendar;
    private final ReviewScope scope;

    public ProjectionHandler(ProjectionStore store, OrganizationCalendar calendar, ReviewScope scope) {
        this.store = store;
        this.calendar = calendar;
        this.scope = scope;
    }

    @IdempotentConsumer("timesheet.projections")
    @Transactional
    public void apply(EventEnvelope<JsonNode> event) {
        Objects.requireNonNull(event.eventId());
        Instant version = Objects.requireNonNull(event.occurredAt());
        JsonNode p = Objects.requireNonNull(event.payload());
        store.lock(true);
        LocalDate effective = calendar.date(version);
        switch (event.type()) {
            case "user.verified" -> store.employee(id(p, "userId"), true, version);
            case "user.assignment.updated" ->
                scope.assignment(
                        id(p, "userId"),
                        nullableId(p, "departmentId"),
                        Instant.parse(p.path("assignedAt").asText()));
            case "employee.profile.updated" ->
                scope.profile(id(p, "userId"), nullableId(p, "reportingManagerUserId"), version);
            case "project.created", "project.updated", "project.deleted" -> {
                JsonNode project = Objects.requireNonNull(p.get("project"));
                String name = project.path("name").asText("");
                if (name.isBlank() || name.length() > 100)
                    throw new IllegalArgumentException("Project name is required");
                store.project(
                        new ProjectSnapshot(
                                id(project, "id"),
                                id(project, "teamId"),
                                name,
                                event.type().equals("project.deleted"),
                                date(project, "startDate"),
                                date(project, "endDate")),
                        version,
                        effective);
            }
            case "team.member.added", "team.member.removed" -> {
                // Legacy joinedAt is a local server timestamp, not a reliable organization instant.
                // Use the common envelope's UTC timestamp for both additions and removals.
                store.membership(
                        new Membership(
                                id(p, "teamId"), id(p, "userId"), event.type().equals("team.member.added")),
                        version,
                        effective);
            }
            default -> throw new IllegalArgumentException("Unsupported event type");
        }
    }

    private Long nullableId(JsonNode p, String name) {
        JsonNode n = p.get(name);
        if (n == null) throw new IllegalArgumentException(name + " must be present (nullable)");
        return n.isNull() ? null : id(p, name);
    }

    private long id(JsonNode node, String name) {
        JsonNode n = node.get(name);
        if (n == null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() <= 0)
            throw new IllegalArgumentException(name + " must be a positive integer");
        return n.asLong();
    }

    private LocalDate date(JsonNode p, String name) {
        JsonNode n = p.get(name);
        return n == null || n.isNull() ? null : LocalDate.parse(n.asText());
    }
}
