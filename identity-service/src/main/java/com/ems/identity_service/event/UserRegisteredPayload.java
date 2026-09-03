package com.ems.identity_service.event;

import java.time.Instant;

/**
 * Body of the {@value #TYPE} event: what another service needs to know about a new account
 * without calling back into identity-service for it.
 *
 * <p>Deliberately thin. This is a published contract, so anything added here is something
 * every consumer may come to depend on — and the password hash, ban flag and role
 * assignments are identity-service's business, not theirs.
 */
public record UserRegisteredPayload(Long userId, String email, String username, Instant occurredAt) {

    /** Event type, and the routing key it is published under. */
    public static final String TYPE = "user.registered";

    /** The kind of thing the event is about, as recorded in the outbox. */
    public static final String AGGREGATE_TYPE = "user";
}
