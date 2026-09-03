package com.ems.identity_service.exception;

/**
 * Thrown when correct credentials are presented for an account whose email address has not
 * been verified. Deliberately raised only <em>after</em> the password has been checked, so
 * that it cannot be used to ask whether an address is registered.
 */
public class EmailNotVerifiedException extends RuntimeException {

    public EmailNotVerifiedException(String message) {
        super(message);
    }
}
