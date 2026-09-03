package com.ems.identity_service.exception;

/**
 * Thrown when a password reset token cannot be redeemed. Carries the reason as an
 * {@link AuthErrorCode}, because the page wording differs: an expired link is worth a "send me
 * another" button, an unrecognised one is either a typo or a forgery.
 *
 * <p>Unlike {@link InvalidVerificationTokenException}, a spent token <em>is</em> a failure and
 * does come through here. Verifying twice reaches the state the caller wanted, so it can be
 * answered as success; resetting twice cannot — the second caller has a password in hand that
 * was never applied, and telling them it worked would leave them locked out with no way to
 * connect the two.
 */
public class InvalidPasswordResetTokenException extends RuntimeException {

    private final String code;

    private InvalidPasswordResetTokenException(String message, String code) {
        super(message);
        this.code = code;
    }

    /**
     * No such token. Also the answer for a value that is not a UUID, and for one belonging to
     * an account that has since been deleted — to the person holding the link these are the
     * same thing, and separating them only tells a prober how the value is stored.
     */
    public static InvalidPasswordResetTokenException unknown() {
        return new InvalidPasswordResetTokenException(
                "This password reset link is not valid. Request a new one.",
                AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }

    public static InvalidPasswordResetTokenException expired() {
        return new InvalidPasswordResetTokenException(
                "This password reset link has expired. Request a new one.", AuthErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
    }

    /**
     * Already spent — either redeemed, or retired by a later request. Both are reported the
     * same way and neither says whether the password was actually changed: a link that reached
     * this state may have been used by someone who was not the account owner, and the account
     * owner is told what happened by the password-changed mail, not by this response.
     */
    public static InvalidPasswordResetTokenException used() {
        return new InvalidPasswordResetTokenException(
                "This password reset link has already been used. Request a new one if you still need it.",
                AuthErrorCode.PASSWORD_RESET_TOKEN_USED);
    }

    public String getCode() {
        return code;
    }
}
