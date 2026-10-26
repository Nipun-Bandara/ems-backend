package com.ems.org_service.exception;

public class TeamNotFoundException extends RuntimeException {
    public TeamNotFoundException(Long id) {
        super("Team not found with ID: " + id);
    }
}
