package com.ems.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps validation failures and otherwise unhandled exceptions onto {@link ErrorResponse}.
 *
 * <p>Services extend this and add their own domain handlers; the subclass carries its own
 * {@code @RestControllerAdvice} because the annotation is not inherited.
 */
@RestControllerAdvice
public class BaseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(BaseExceptionHandler.class);

    /**
     * Handles @Valid failures on @RequestBody (e.g. missing required fields).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message, request);
    }

    /**
     * Handles Bean Validation failures triggered outside of @RequestBody binding
     * (e.g. constraints on @RequestParam or on a manually invoked Validator).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
                .collect(Collectors.joining("; "));

        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                ex.getMessage() != null ? ex.getMessage() : "An unexpected error occurred",
                request);
    }

    protected ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status, String error, String message, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(ErrorResponse.of(status.value(), error, message, request.getRequestURI()));
    }
}
