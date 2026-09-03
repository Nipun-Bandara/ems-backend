package com.ems.identity_service.exception;

/**
 * Thrown when a verification mail was already sent to an address within the cooldown.
 *
 * <p>The limit is on the address, not on the caller's IP: what it protects is the inbox of
 * whoever owns that address, who did not ask to be mailed and cannot be made to care how many
 * machines the requests came from.
 */
public class ResendTooSoonException extends RuntimeException {

    public ResendTooSoonException(String message) {
        super(message);
    }
}
