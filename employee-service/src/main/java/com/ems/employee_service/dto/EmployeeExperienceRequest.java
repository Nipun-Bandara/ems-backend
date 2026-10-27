package com.ems.employee_service.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

public record EmployeeExperienceRequest(
        @NotBlank @Size(max = 200) String company,
        @NotBlank @Size(max = 200) String title,
        @NotNull @PastOrPresent LocalDate startDate,
        @PastOrPresent LocalDate endDate,
        @Size(max = 5000) String description,
        @NotNull Boolean isCurrent) {
    @AssertTrue(message = "Current roles must have no end date; previous roles need an end date on or after start date")
    public boolean isDateRangeValid() {
        return isCurrent == null
                || (isCurrent
                        ? endDate == null
                        : endDate != null && (startDate == null || !endDate.isBefore(startDate)));
    }
}
