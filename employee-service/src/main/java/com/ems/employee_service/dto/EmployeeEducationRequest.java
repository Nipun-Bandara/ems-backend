package com.ems.employee_service.dto;

import jakarta.validation.constraints.*;

public record EmployeeEducationRequest(
        @NotBlank @Size(max = 200) String institution,
        @NotBlank @Size(max = 200) String qualification,
        @Size(max = 200) String fieldOfStudy,
        @NotNull @Min(1900) @Max(2200) Integer startYear,
        @Min(1900) @Max(2200) Integer endYear,
        @Size(max = 100) String grade) {
    @AssertTrue(message = "End year must be on or after start year")
    public boolean isYearRangeValid() {
        return startYear == null || endYear == null || endYear >= startYear;
    }
}
