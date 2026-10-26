package com.ems.org_service.dto.request;

import jakarta.validation.constraints.*;

public record TeamRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Positive Long departmentId,
        @Positive Long leadUserId) {}
