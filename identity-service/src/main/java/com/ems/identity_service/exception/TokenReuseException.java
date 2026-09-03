package com.ems.identity_service.exception;

/**
 * Thrown when a refresh token that verifies is presented after it has already been redeemed.
 *
 * <p>By the time this is thrown the token's whole family has been revoked. That is heavier than
 * refusing the one token, and deliberately so: the two explanations for a spent token coming back
 * — a client retrying a refresh whose response it lost, and a copy of the token being used by
 * someone else — cannot be told apart from here, and only one of them is safe to keep the session
 * for. Signing the real user out is the recoverable half of the choice.
 *
 * <p>Separate from {@link InvalidTokenException}, which is every other reason a refresh fails, so
 * that the client can say something different: see {@link AuthErrorCode#TOKEN_REUSE_DETECTED}.
 */
public class TokenReuseException extends RuntimeException {

    public TokenReuseException(String message) {
        super(message);
    }
}
