package com.ems.leave_service.projection;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public record EmployeeSnapshot(
        @NotNull Boolean active,
        @Positive Long departmentId,
        @Positive Long reportingManagerUserId,
        @NotNull @PastOrPresent Instant asOf) {}
