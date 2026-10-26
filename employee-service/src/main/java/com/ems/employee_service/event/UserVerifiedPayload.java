package com.ems.employee_service.event;

import java.time.Instant;
import java.util.Objects;

public record UserVerifiedPayload(Long userId, String email, String username, Instant verifiedAt) {

    public static final String TYPE = "user.verified";

    public UserVerifiedPayload {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(verifiedAt, "verifiedAt is required");
        if (userId <= 0 || email == null || email.isBlank() || username == null || username.isBlank()) {
            throw new IllegalArgumentException("A verified user requires a positive userId, email and username");
        }
    }
}
