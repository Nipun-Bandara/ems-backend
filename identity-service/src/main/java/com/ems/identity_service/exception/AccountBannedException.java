package com.ems.identity_service.exception;

public class AccountBannedException extends RuntimeException {

    public AccountBannedException(String message) {
        super(message);
    }
}
