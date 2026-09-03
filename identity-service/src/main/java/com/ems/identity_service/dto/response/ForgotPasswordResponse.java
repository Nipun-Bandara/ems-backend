package com.ems.identity_service.dto.response;

/**
 * Answer to a forgot-password request. Always this, always a 200.
 *
 * <p>There is deliberately nothing in here that varies: no flag for whether an account was
 * found, no count of what was sent. The endpoint is public and unauthenticated, so any
 * difference between "we mailed you" and "we did not" would turn it into a way of asking which
 * addresses are registered — which is the one thing a password reset flow must not answer.
 *
 * @param message wording for the page to show, identical in every case
 */
public record ForgotPasswordResponse(String message) {

    private static final String GENERIC =
            "If an account exists for that address, a link to reset its password is on its way.";

    public static ForgotPasswordResponse generic() {
        return new ForgotPasswordResponse(GENERIC);
    }
}
