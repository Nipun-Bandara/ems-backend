package com.ems.identity_service.controller;

import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.dto.response.UserResponse;
import com.ems.identity_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<List<UserResponse>> getUsers(@RequestParam(value = "status", required = false) String status) {
        if ("unassigned".equals(status)) {
            return ResponseEntity
                    .ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(userService.getUnassignedUsers());
        }
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.getAllUsers());
    }

    @GetMapping("/{userId}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long userId) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.getUserById(userId));
    }

    @PutMapping("/{userId}/assignment")
    @PreAuthorize("hasRole('DEPARTMENT_HEAD') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<UserResponse> assignRoleAndDepartment(
            @PathVariable Long userId,
            @Valid @RequestBody AssignRoleAndDepartmentRequest request) {
        return ResponseEntity
                .ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(userService.assignRoleAndDepartment(userId, request));
    }
}
