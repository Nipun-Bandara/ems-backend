package com.ems.timesheet_service;

import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;

public final class AttendanceModels {
    private AttendanceModels() {}

    // The caller supplies an opaque operation ID, never a timestamp or employee identity.
    public record ClockIn(@NotNull UUID requestId) {}

    public record Correction(
            @NotNull @Min(0) Long version,
            @NotNull @PastOrPresent Instant clockIn,
            @PastOrPresent Instant clockOut,
            @NotBlank @Size(max = 500) String reason) {}

    public record Session(
            UUID id, long employeeId, Instant clockIn, Instant clockOut, long version, Long durationSeconds) {}

    public record Audit(
            UUID id,
            long actorId,
            Instant correctedAt,
            String reason,
            Instant beforeIn,
            Instant beforeOut,
            Instant afterIn,
            Instant afterOut,
            long version) {}

    public record State(String timezone, Instant serverTime, boolean hr, List<Session> sessions) {}

    public record History(String timezone, List<Session> items, int page, int size, long total) {}

    public record Day(LocalDate date, long recordedSeconds) {}

    public record Summary(String timezone, LocalDate from, LocalDate to, List<Day> days, int openSessions) {}
}
