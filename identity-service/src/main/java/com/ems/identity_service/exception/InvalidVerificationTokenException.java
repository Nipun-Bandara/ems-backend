package com.ems.identity_service.exception;

/**
 * Thrown when a verification token cannot be redeemed. Carries the reason as an
 * {@link AuthErrorCode}, because the page that shows the failure words an expired link
 * differently from an unrecognised one — the first is worth a resend button, the second is
 * either a typo or a forgery.
 *
 * <p>A token that was already spent is not a failure and does not come through here; see
 * {@link com.ems.identity_service.dto.response.VerificationResponse}.
 */
public class InvalidVerificationTokenException extends RuntimeException {

    private final String code;

    private InvalidVerificationTokenException(String message, String code) {
        super(message);
        this.code = code;
    }

    public static InvalidVerificationTokenException unknown() {
        return new InvalidVerificationTokenException(
                "This verification link is not valid. Request a new one.", AuthErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    public static InvalidVerificationTokenException expired() {
        return new InvalidVerificationTokenException(
                "This verification link has expired. Request a new one.", AuthErrorCode.VERIFICATION_TOKEN_EXPIRED);
    }

    /**
     * A token retired by a later resend, on an account that is still unverified. Reported as
     * unusable rather than as "already verified" — the account is not, and telling the holder
     * of an old link that they are done would send them to a sign-in that refuses them.
     */
    public static InvalidVerificationTokenException superseded() {
        return new InvalidVerificationTokenException(
                "This link was replaced by a newer one. Use the most recent verification email, "
                        + "or request another.",
                AuthErrorCode.VERIFICATION_TOKEN_INVALID);
    }

    public String getCode() {
        return code;
    }
}
