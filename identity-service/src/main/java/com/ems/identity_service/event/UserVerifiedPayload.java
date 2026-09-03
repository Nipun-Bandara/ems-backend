package com.ems.identity_service.event;

import java.time.Instant;

/**
 * Body of the {@value #TYPE} event: an account's email address has been proved reachable.
 *
 * <p>Published once per account, by the verify request that spends the token. A second click
 * on the same link is answered idempotently and emits nothing, so a consumer of this event is
 * being told about a state change rather than about a request.
 *
 * <p>Carries no token: the one that caused this is spent by the time the event exists.
 */
public record UserVerifiedPayload(Long userId, String email, String username, Instant verifiedAt) {

    /** Event type, and the routing key it is published under. */
    public static final String TYPE = "user.verified";

    /** The kind of thing the event is about, as recorded in the outbox. */
    public static final String AGGREGATE_TYPE = "user";
}
