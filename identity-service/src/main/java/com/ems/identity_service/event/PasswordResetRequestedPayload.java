package com.ems.identity_service.event;

import java.time.Instant;

/**
 * Body of the {@value #TYPE} event: someone asked for a link to reset an account's password.
 *
 * <p>Published only when the address actually belongs to an account. A request for an address
 * with no account is answered identically to a real one but emits nothing, so the presence of
 * this event already means the account exists — consumers do not have to check.
 *
 * @param resetToken the single-use token for the reset link. It travels on the event for the
 *     same reason the verification token does on {@link UserRegisteredPayload}:
 *     identity-service mints it but does not send mail, and a callback for it would put a
 *     synchronous hop in the middle of an asynchronous pipeline. This makes the event a
 *     secret-bearing one — readable by anything bound to {@code user.*}, and recorded in
 *     notification-service's audit_log — which is part of why the token is only good for an
 *     hour.
 */
public record PasswordResetRequestedPayload(
        Long userId, String email, String username, String resetToken, Instant occurredAt) {

    /** Event type, and the routing key it is published under. */
    public static final String TYPE = "password.reset.requested";

    /** The kind of thing the event is about, as recorded in the outbox. */
    public static final String AGGREGATE_TYPE = "user";
}
