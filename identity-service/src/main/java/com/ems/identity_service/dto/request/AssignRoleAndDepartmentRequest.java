package com.ems.identity_service.dto.request;

import com.ems.identity_service.enums.Role;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AssignRoleAndDepartmentRequest {

    private Role role;

    private Long departmentId;
}
