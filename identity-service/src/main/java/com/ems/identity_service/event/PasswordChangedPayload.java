package com.ems.identity_service.event;

import java.time.Instant;

/**
 * Body of the {@value #TYPE} event: an account's password was replaced through a reset.
 *
 * <p>The mail this causes is a security notice rather than a courtesy. It is the only thing
 * that reaches the real owner if someone else completed the reset, so it goes to the address
 * on the account and carries no link to click — a notice that asks for action is the shape a
 * phishing mail takes, and this one is telling the reader something already happened.
 *
 * <p>Carries no token: the one that caused this is spent by the time the event exists.
 */
public record PasswordChangedPayload(Long userId, String email, String username, Instant changedAt) {

    /** Event type, and the routing key it is published under. */
    public static final String TYPE = "password.changed";

    /** The kind of thing the event is about, as recorded in the outbox. */
    public static final String AGGREGATE_TYPE = "user";
}
