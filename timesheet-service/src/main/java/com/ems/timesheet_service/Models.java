package com.ems.timesheet_service;

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
        REJECTED
    }

    public record Entry(
            @NotNull LocalDate date,
            @Positive long projectId,
            @NotNull @Min(0) @Max(1440) Integer minutes,
            @NotBlank @Size(max = 240) String description) {}

    public record Create(@NotNull LocalDate weekStart) {}

    public record Save(
            @NotNull @Min(0) Long version,
            @NotNull @Size(max = 500) List<@NotNull @Valid Entry> entries) {}

    public record Submit(@NotNull @Min(0) Long version) {}

    public record Sheet(
            UUID id,
            long employeeId,
            LocalDate weekStart,
            Status status,
            long version,
            List<Entry> entries,
            Instant createdAt,
            Instant updatedAt,
            Instant submittedAt,
            long createdBy,
            long updatedBy,
            Long submittedBy,
            Instant reviewedAt,
            Long reviewedBy,
            String reviewReason) {}

    public record Calendar(String timezone, LocalDate currentWeekStart) {}

    public record Project(long id, String name, List<LocalDate> eligibleDates) {}

    public record Employee(@Positive long userId, boolean active) {}

    public record ProjectSnapshot(
            @Positive long id,
            @Positive long teamId,
            @NotBlank @Size(max = 100) String name,
            boolean deleted,
            LocalDate startDate,
            LocalDate endDate) {}

    public record Membership(
            @Positive long teamId, @Positive long userId, boolean member) {}

    public record Backfill(
            @NotNull @PastOrPresent Instant asOf,
            @NotNull LocalDate effectiveDate,
            @NotNull @Size(max = 1000) List<@NotNull @Valid Employee> employees,
            @NotNull @Size(max = 1000) List<@NotNull @Valid ProjectSnapshot> projects,
            @NotNull @Size(max = 1000) List<@NotNull @Valid Membership> memberships) {}
}
