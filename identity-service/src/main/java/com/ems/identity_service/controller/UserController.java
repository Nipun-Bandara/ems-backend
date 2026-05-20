package com.ems.identity_service.controller;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.request.UsersRequest;
import com.ems.identity_service.dto.response.PaginatedUserResponse;
import com.ems.identity_service.dto.response.UserRecord;
import com.ems.identity_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<PaginatedUserResponse> getUsers(
            @Valid @RequestBody UsersRequest usersRequest) {

        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.getUsers(usersRequest));
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserRecord> getUserById(@PathVariable Long userId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.getUserById(userId));
    }

    @PutMapping("/{userId}/assignment")
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<UserRecord> assignRoleAndDepartment(
            @PathVariable Long userId,
            @Valid @RequestBody AssignRoleAndDepartmentRequest request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.assignRoleAndDepartment(userId, request));
    }
}
