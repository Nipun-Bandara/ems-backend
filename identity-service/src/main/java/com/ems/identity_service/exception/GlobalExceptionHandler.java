package com.ems.identity_service.exception;

import com.ems.common.error.BaseExceptionHandler;
import com.ems.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Identity-specific handlers; validation and unhandled errors come from
 * {@link BaseExceptionHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends BaseExceptionHandler {

    @ExceptionHandler(AccountBannedException.class)
    public ResponseEntity<ErrorResponse> handleAccountBanned(AccountBannedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request);
    }

    /**
     * A 403 that a client has to tell apart from every other 403, hence the code: the sign-in
     * page offers a "resend the link" button for this one and a "check your password" message
     * for the rest.
     */
    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(
            EmailNotVerifiedException ex, HttpServletRequest request) {
        return buildResponse(
                HttpStatus.FORBIDDEN, "Forbidden", ex.getMessage(), request, AuthErrorCode.EMAIL_NOT_VERIFIED);
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(
            InvalidVerificationTokenException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request, ex.getCode());
    }

    @ExceptionHandler(ResendTooSoonException.class)
    public ResponseEntity<ErrorResponse> handleResendTooSoon(ResendTooSoonException ex, HttpServletRequest request) {
        return buildResponse(
                HttpStatus.TOO_MANY_REQUESTS,
                "Too Many Requests",
                ex.getMessage(),
                request,
                AuthErrorCode.RESEND_TOO_SOON);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.UNAUTHORIZED, "Unauthorized", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    /**
     * Handles 403s thrown explicitly in service logic
     * (e.g. officer trying to act on a case not assigned to them).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return buildResponse(HttpStatus.FORBIDDEN, "Access Denied", ex.getMessage(), request);
    }
}
