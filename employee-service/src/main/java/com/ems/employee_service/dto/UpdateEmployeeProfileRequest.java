package com.ems.employee_service.dto;

import jakarta.validation.constraints.*;
import java.time.LocalDate;

/** PUT replaces editable fields. Assignment and avatar keys have dedicated, trusted writers. */
public record UpdateEmployeeProfileRequest(
        @NotBlank @Size(max = 50) String firstName,
        @NotBlank @Size(max = 50) String lastName,

        @Size(max = 30) @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{5,29}$")
        String phone,

        @Past LocalDate dateOfBirth,
        @Size(max = 500) @Pattern(regexp = "(?s).*\\S.*") String address,
        @Size(max = 100) @Pattern(regexp = "(?s).*\\S.*") String emergencyContactName,

        @Size(max = 30) @Pattern(regexp = "^\\+?[0-9][0-9 ()-]{5,29}$")
        String emergencyContactPhone,

        @Positive Long designationId,
        @PastOrPresent LocalDate joinDate,
        @Positive Long reportingManagerUserId) {}
