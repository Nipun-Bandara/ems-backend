package com.ems.leave_service.request;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class WorkflowModels {
    private WorkflowModels() {}

    public record Start(
            @NotNull @PositiveOrZero Long version,
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}

    public record Workflow(
            UUID id,
            UUID requestId,
            String operation,
            String state,
            UUID stepId,
            int attempts,
            Instant deadline,
            Instant nextAttempt,
            String error) {}

    public record Absence(
            UUID requestId, long employeeId, LocalDate startDate, LocalDate endDate, String dayFraction) {}

    public record Calendar(String timezone, List<String> workweek, List<Absence> absences) {}
}
