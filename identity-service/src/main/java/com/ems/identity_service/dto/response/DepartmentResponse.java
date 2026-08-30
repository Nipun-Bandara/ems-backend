package com.ems.identity_service.dto.response;

import java.time.LocalDateTime;
import lombok.*;

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
