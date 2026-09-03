package com.ems.identity_service.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResetPasswordRequest {

    @NotBlank(message = "Token is required")
    private String token;

    /**
     * Only checked for presence here, which is the same rule
     * {@link RegisterRequest#getPassword()} applies. A reset that refused passwords
     * registration accepts would strand users who chose one before this endpoint existed, so
     * the strength rule belongs in one place applied to both — not tightened here alone.
     */
    @NotBlank(message = "New password is required")
    private String newPassword;
}
