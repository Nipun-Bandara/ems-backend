package com.ems.identity_service.security;

import com.ems.common.security.GatewayAuthenticationFilter;
import org.springframework.security.core.Authentication;

/** Reads the caller out of the {@link Authentication} the gateway established. */
public final class AuthenticatedUser {

    private AuthenticatedUser() {}

    /**
     * The id the gateway forwarded in {@code X-User-Id}, which
     * {@link GatewayAuthenticationFilter} stores as the principal name. Callers reach this only
     * through an authenticated request, so a principal that is not a user id is a bug in the
     * gateway rather than bad input.
     */
    public static Long requireUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalArgumentException("User not authenticated");
        }
        try {
            return Long.valueOf(authentication.getName());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Authenticated principal is not a user id: " + authentication.getName());
        }
    }
}
