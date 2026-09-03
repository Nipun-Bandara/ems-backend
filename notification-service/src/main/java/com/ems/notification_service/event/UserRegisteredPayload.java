package com.ems.notification_service.event;

import java.time.Instant;

/**
 * This service's own view of the {@value #TYPE} event body.
 *
 * <p>A copy of identity-service's record, on purpose. The contract between the two services
 * is the JSON on the broker, not a shared class: depending on identity-service to get this
 * type would put a compile-time edge between two services whose whole point is that they only
 * meet at the exchange, and would make identity-service's internal refactors break this build.
 *
 * <p>Fields are mirrored in full rather than narrowed to the ones the mail needs, so that what
 * this service believes the contract to be is written down where it can be compared with the
 * published one.
 *
 * @param verificationToken the single-use token this service builds the verification link
 *     from. It is a credential for the account, so it goes into the link and nowhere else —
 *     never into a log line.
 */
public record UserRegisteredPayload(
        Long userId, String email, String username, String verificationToken, Instant occurredAt) {

    /** Event type, and the routing key it arrives under. */
    public static final String TYPE = "user.registered";
}
