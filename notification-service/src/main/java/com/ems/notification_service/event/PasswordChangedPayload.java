package com.ems.notification_service.event;

import java.time.Instant;

/**
 * Consumer-side view of identity-service's {@code password.changed}.
 *
 * <p>The mail this causes is the only warning an account owner gets if the reset was not
 * theirs, so it is worth sending even though nothing is asked of the reader.
 */
public record PasswordChangedPayload(Long userId, String email, String username, Instant changedAt) {

    /** Event type, and the routing key it arrives under. */
    public static final String TYPE = "password.changed";
}
