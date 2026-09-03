package com.ems.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Canonical error body for every EMS service.
 *
 * <p>Serialized by Jackson only — services must never hand-build this as a JSON string.
 * Null fields are omitted so callers that cannot supply a {@code path} still get a
 * {@code {status, error, message}} body.
 *
 * @param code an optional stable identifier for the specific failure, for a client that has
 *     to branch on <em>which</em> 403 this is rather than merely report it. Absent from most
 *     responses on purpose: a status and a sentence is all a client normally needs, and every
 *     code added here is one a caller may come to switch on. {@code error} and {@code message}
 *     stay free to be reworded; a code, once published, does not.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(int status, String error, String message, String path, Instant timestamp, String code) {

    public static ErrorResponse of(int status, String error, String message, String path) {
        return of(status, error, message, path, null);
    }

    public static ErrorResponse of(int status, String error, String message, String path, String code) {
        return new ErrorResponse(status, error, message, path, Instant.now(), code);
    }
}
