package com.ems.employee_service.dto;

import com.ems.employee_service.entity.Proficiency;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;

public record EmployeeSkillRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull Proficiency proficiency,

        @NotNull @DecimalMin("0.0") @DecimalMax("100.0") @Digits(integer = 3, fraction = 1)
        BigDecimal yearsOfExperience) {}
