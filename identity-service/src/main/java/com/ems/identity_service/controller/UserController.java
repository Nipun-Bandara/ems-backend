package com.ems.identity_service.controller;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.response.PaginatedUserResponse;
import com.ems.identity_service.dto.response.UserRecord;
import com.ems.identity_service.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

// @Validated routes the @RequestParam constraints below through the method-validation proxy, so
// they surface as ConstraintViolationException (400 via BaseExceptionHandler) rather than the
// HandlerMethodValidationException that the catch-all Exception handler would report as a 500.
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PaginatedUserResponse> getUsers(
            @RequestParam(defaultValue = "true") Boolean assigned,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.getUsers(assigned, page, limit));
    }

    // The gateway filter stores the forwarded user id as a bare String principal, so the path
    // variable is compared as a String: SpEL equality between a Long and a String is always false.
    @GetMapping("/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN') or hasRole('DEPARTMENT_HEAD')"
            + " or #userId.toString() == authentication.principal")
    public ResponseEntity<UserRecord> getUserById(@PathVariable Long userId) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(userService.getUserById(userId));
    }

    @PutMapping("/{userId}/assignment")
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<UserRecord> assignRoleAndDepartment(
            @PathVariable Long userId, @Valid @RequestBody AssignRoleAndDepartmentRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.assignRoleAndDepartment(userId, request));
    }
}
