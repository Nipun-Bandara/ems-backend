package com.ems.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes an {@link ErrorResponse} straight to the servlet response for the places that sit
 * outside {@code @RestControllerAdvice} — servlet filters, authentication entry points and
 * access denied handlers.
 */
public final class ErrorResponseWriter {

    private ErrorResponseWriter() {}

    public static void write(
            ObjectMapper objectMapper,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String error,
            String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Must precede getWriter(), which otherwise locks in the container default.
        response.setCharacterEncoding(StandardCharsets.UTF_8);
        objectMapper.writeValue(
                response.getWriter(), ErrorResponse.of(status.value(), error, message, request.getRequestURI()));
    }
}
