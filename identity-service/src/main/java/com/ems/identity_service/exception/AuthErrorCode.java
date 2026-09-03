package com.ems.identity_service.exception;

/**
 * The {@link com.ems.common.error.ErrorResponse#code() error codes} this service publishes.
 *
 * <p>Each one exists because a client has to tell the failure apart from the others sharing
 * its status: a login can be refused for a wrong password or for an unverified address, and
 * only one of those is worth offering a "resend the link" button for. The strings are a
 * contract with the frontend — reword the messages freely, but not these.
 */
public final class AuthErrorCode {

    /** Credentials were right, but the account's email address has not been verified. */
    public static final String EMAIL_NOT_VERIFIED = "EMAIL_NOT_VERIFIED";

    /** The verification token is unknown, or was superseded by a later one. */
    public static final String VERIFICATION_TOKEN_INVALID = "VERIFICATION_TOKEN_INVALID";

    /** The verification token was real, but issued too long ago. */
    public static final String VERIFICATION_TOKEN_EXPIRED = "VERIFICATION_TOKEN_EXPIRED";

    /** A verification mail went to this address less than the cooldown ago. */
    public static final String RESEND_TOO_SOON = "RESEND_TOO_SOON";

    /** The password reset token is unknown, or was never one we issued. */
    public static final String PASSWORD_RESET_TOKEN_INVALID = "PASSWORD_RESET_TOKEN_INVALID";

    /** The password reset token was real, but issued more than an hour ago. */
    public static final String PASSWORD_RESET_TOKEN_EXPIRED = "PASSWORD_RESET_TOKEN_EXPIRED";

    /** The password reset token was already spent, or retired by a later request. */
    public static final String PASSWORD_RESET_TOKEN_USED = "PASSWORD_RESET_TOKEN_USED";

    private AuthErrorCode() {}
}
