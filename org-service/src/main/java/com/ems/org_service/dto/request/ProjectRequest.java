package com.ems.org_service.dto.request;

import com.ems.org_service.entity.ProjectStatus;
import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record ProjectRequest(
        @NotBlank @Size(max = 100) String name,
        @Size(max = 2000) String description,
        @NotNull @Positive Long teamId,
        @NotNull ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate) {
    @AssertTrue(message = "End date must be on or after start date")
    public boolean isDateRangeValid() {
        return startDate == null || endDate == null || !endDate.isBefore(startDate);
    }
}
