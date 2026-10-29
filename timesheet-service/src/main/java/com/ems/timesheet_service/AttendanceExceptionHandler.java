package com.ems.timesheet_service;

import com.ems.common.error.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(-1)
@RestControllerAdvice(assignableTypes = AttendanceController.class)
public class AttendanceExceptionHandler {
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> conflict(Exception ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ErrorResponse.of(
                        409,
                        "Conflict",
                        "Attendance overlaps or the operation ID is already in use; reload server state",
                        request.getRequestURI(),
                        null));
    }
}
