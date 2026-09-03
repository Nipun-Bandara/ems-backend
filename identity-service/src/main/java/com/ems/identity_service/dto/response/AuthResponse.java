package com.ems.identity_service.dto.response;

import com.ems.identity_service.enums.Role;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String refreshToken;
    private Long userId;
    private String email;
    private String username;
    private Long departmentId;
    private String departmentName;
    private List<Role> roles;
    private Boolean isBanned;

    /**
     * Whether the account's email address has been verified. Present on every response for
     * symmetry, but it is only ever false on the one returned by registration — every other
     * response here comes from a path an unverified account cannot reach.
     */
    private Boolean emailVerified;
}
