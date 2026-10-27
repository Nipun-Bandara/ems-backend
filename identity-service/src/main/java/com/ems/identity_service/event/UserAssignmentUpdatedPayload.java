package com.ems.identity_service.event;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record UserAssignmentUpdatedPayload(
        @NotNull @Positive Long userId,
        @Positive Long departmentId,
        @NotNull Instant assignedAt) {
    public static final String TYPE = "user.assignment.updated";
}
