package com.ems.identity_service.dto.response;

import com.ems.identity_service.enums.Role;
import java.util.List;
import lombok.*;

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
