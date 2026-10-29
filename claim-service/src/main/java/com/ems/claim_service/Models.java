package com.ems.claim_service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class Models {
    private Models() {}

    public enum Status {
        DRAFT,
        SUBMITTED,
        APPROVED,
        REJECTED,
        WITHDRAWN
    }

    public record Line(
            @NotBlank @Size(max = 80) String category,
            @NotNull LocalDate expenseDate,
            @NotBlank @Size(max = 500) String description,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,

            @NotNull @Pattern(regexp = "[0-9]{1,17}(\\.[0-9]{1,2})?")
            String amount) {}

    public record Create(
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency) {}

    public record Save(
            @NotNull @Min(0) Long version,
            @NotNull @Size(max = 100) List<@NotNull @Valid Line> lines) {}

    public record Transition(
            @NotNull @Min(0) Long version, @Size(max = 500) String reason) {}

    public record History(UUID id, String action, long actorId, String reason, long version, Instant occurredAt) {}

    public record Claim(
            UUID id,
            long employeeId,
            String currency,
            Status status,
            String total,
            long version,
            List<Line> lines,
            Instant createdAt,
            Instant updatedAt,
            Instant submittedAt,
            List<History> history) {}

    public record Permissions(boolean active, boolean reviewer, String currency) {}

    public record EmployeeScope(
            @NotNull Boolean active,
            @Positive Long departmentId,
            @Positive Long reportingManagerUserId,
            @NotNull @PastOrPresent Instant asOf) {}

    public enum Role {
        HR_MANAGER
    }

    public record Reviewer(
            Role role, @NotNull @PastOrPresent Instant asOf) {}
}
