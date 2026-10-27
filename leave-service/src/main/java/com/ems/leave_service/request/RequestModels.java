package com.ems.leave_service.request;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

public final class RequestModels {
    private RequestModels() {}

    public enum Fraction {
        FULL,
        HALF
    }

    public enum Status {
        PENDING,
        APPROVAL_IN_PROGRESS,
        APPROVED,
        REJECTED,
        CANCELLED,
        APPROVAL_FAILED
    }

    public enum ReviewerRole {
        DEPARTMENT_HEAD,
        HR_MANAGER
    }

    public record EstimateInput(
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull Fraction dayFraction) {}

    public record Estimate(String calculatedDays) {}

    public record Submit(
            @NotNull UUID typeId,
            @NotNull LocalDate startDate,
            @NotNull LocalDate endDate,
            @NotNull Fraction dayFraction,
            @NotBlank @Size(max = 500) String reason,
            @NotBlank @Size(max = 100) String idempotencyKey) {}

    public record Action(
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String reason,
            @PositiveOrZero Long version) {
        public Action(String key, String reason) {
            this(key, reason, null);
        }
    }

    public record Route(
            @Positive long approverId,
            @NotBlank @Size(max = 100) String idempotencyKey,
            @NotBlank @Size(max = 500) String reason) {}

    public record ReviewerSnapshot(
            ReviewerRole role, @NotNull @PastOrPresent Instant asOf) {}

    public record LeaveRequest(
            UUID id,
            long employeeId,
            UUID typeId,
            UUID entitlementId,
            long approverId,
            LocalDate startDate,
            LocalDate endDate,
            Fraction dayFraction,
            String calculatedDays,
            String reason,
            Status status,
            long version,
            Instant createdAt,
            Instant updatedAt) {}
}
