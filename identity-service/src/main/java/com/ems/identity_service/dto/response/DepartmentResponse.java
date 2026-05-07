package com.ems.identity_service.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DepartmentResponse {

    private Long departmentId;
    private String departmentName;
    private String description;
    private LocalDateTime createdAt;
}
