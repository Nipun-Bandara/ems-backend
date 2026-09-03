package com.ems.notification_service.event;

import java.time.Instant;

/**
 * Consumer-side view of identity-service's {@code password.reset.requested}.
 *
 * <p>Duplicated rather than shared, as {@link UserRegisteredPayload} is: the two services are
 * deployed separately, and a record in a common jar would make every event contract a reason
 * to release both. Only the fields this service reads are declared — Jackson ignores the rest,
 * so identity-service can add to the event without breaking this.
 */
public record PasswordResetRequestedPayload(
        Long userId, String email, String username, String resetToken, Instant occurredAt) {

    /** Event type, and the routing key it arrives under. */
    public static final String TYPE = "password.reset.requested";
}
