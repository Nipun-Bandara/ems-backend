package com.ems.notification_service.event;

import java.time.Instant;

/**
 * This service's own view of the {@value #TYPE} event body.
 *
 * <p>A copy of identity-service's record, for the reason given on {@link UserRegisteredPayload}:
 * the contract between the two services is the JSON on the broker, not a shared class.
 *
 * <p>This is what the welcome email hangs off. Sending it here rather than on registration is
 * the point of the split — a welcome that arrives before the address is confirmed is a welcome
 * sent to an address nobody has shown they can read.
 */
public record UserVerifiedPayload(Long userId, String email, String username, Instant verifiedAt) {

    /** Event type, and the routing key it arrives under. */
    public static final String TYPE = "user.verified";
}
