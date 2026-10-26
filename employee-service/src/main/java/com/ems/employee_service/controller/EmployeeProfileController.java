package com.ems.employee_service.controller;

import com.ems.employee_service.service.EmployeeAvatarService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees/{id}")
@PreAuthorize("#id.toString() == authentication.name or hasRole('SYSTEM_ADMIN')")
public class EmployeeProfileController {
    private final EmployeeAvatarService avatars;

    public EmployeeProfileController(EmployeeAvatarService avatars) {
        this.avatars = avatars;
    }

    public record UploadRequest(
            @NotBlank String contentType,
            @Positive @Max(5242880) long size) {}

    public record ConfirmRequest(@NotBlank @Size(max = 255) String objectKey) {}

    @PostMapping("/avatar/upload-url")
    public ResponseEntity<EmployeeAvatarService.Upload> uploadUrl(
            @PathVariable Long id, @Valid @RequestBody UploadRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.requestUpload(id, request.contentType(), request.size()));
    }

    @PutMapping("/avatar")
    public ResponseEntity<EmployeeAvatarService.Profile> confirm(
            @PathVariable Long id, @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.confirmUpload(id, request.objectKey()));
    }

    @GetMapping
    public ResponseEntity<EmployeeAvatarService.Profile> profile(@PathVariable Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.getProfile(id));
    }
}
