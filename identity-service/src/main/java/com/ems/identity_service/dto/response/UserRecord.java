package com.ems.identity_service.dto.response;

import com.ems.identity_service.enums.Role;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserRecord {

    private Long userId;
    private String username;
    private String email;
    private Long departmentId;
    private String departmentName;
    private Boolean isAssigned;
    private List<Role> roles;
    private Boolean isBanned;
}
