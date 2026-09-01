package com.ems.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Canonical error body for every EMS service.
 *
 * <p>Serialized by Jackson only — services must never hand-build this as a JSON string.
 * Null fields are omitted so callers that cannot supply a {@code path} still get a
 * {@code {status, error, message}} body.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String error, String message, String path, Instant timestamp) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(status, error, message, path, Instant.now());
    }
}
