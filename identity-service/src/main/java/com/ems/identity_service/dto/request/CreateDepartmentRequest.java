package com.ems.identity_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateDepartmentRequest {

    @NotBlank(message = "Department name cannot be blank")
    @Size(min = 2, max = 20, message = "Department name must be between 2 and 20 characters")
    private String departmentName;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;
}
