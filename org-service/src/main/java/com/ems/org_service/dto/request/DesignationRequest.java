package com.ems.org_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DesignationRequest {

    @NotBlank(message = "Designation name cannot be blank")
    @Size(max = 100, message = "Designation name cannot exceed 100 characters")
    private String name;

    @NotNull(message = "Department is required")
    private Long departmentId;

    @Size(max = 500, message = "Description cannot exceed 500 characters")
    private String description;
}
