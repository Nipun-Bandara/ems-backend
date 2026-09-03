package com.ems.identity_service.dto.response;

/**
 * Answer to a verify request that succeeded.
 *
 * @param email the address that is now verified, so the page can say which one
 * @param alreadyVerified true when the token had already been spent. Both outcomes are a 200:
 *     the caller asked for the address to be verified and it is, which is the same answer
 *     either way. The flag exists only so the page can word itself honestly — a user who
 *     double-clicks the link, or whose mail client prefetched it, should not be told the link
 *     is broken.
 */
public record VerificationResponse(String email, boolean alreadyVerified) {

    public static VerificationResponse verified(String email) {
        return new VerificationResponse(email, false);
    }

    public static VerificationResponse alreadyVerified(String email) {
        return new VerificationResponse(email, true);
    }
}
