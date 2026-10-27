package com.ems.employee_service.controller;

import com.ems.employee_service.dto.UpdateEmployeeProfileRequest;
import com.ems.employee_service.service.EmployeeAvatarService;
import com.ems.employee_service.service.EmployeeProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/employees")
public class EmployeeProfileController {
    private final EmployeeAvatarService avatars;
    private final EmployeeProfileService profiles;

    public EmployeeProfileController(EmployeeAvatarService avatars, EmployeeProfileService profiles) {
        this.profiles = profiles;
        this.avatars = avatars;
    }

    public record UploadRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "image/(jpeg|png|webp)")
            String contentType,

            @Positive @Max(5242880) long size) {}

    public record ConfirmRequest(@NotBlank @Size(max = 255) String objectKey) {}

    @PostMapping("/{id}/avatar/upload-url")
    @PreAuthorize("#id.toString() == authentication.name or hasAnyRole('SYSTEM_ADMIN', 'HR_MANAGER')")
    public ResponseEntity<EmployeeAvatarService.Upload> uploadUrl(
            @PathVariable @Positive Long id, @Valid @RequestBody UploadRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.requestUpload(id, request.contentType(), request.size()));
    }

    @PutMapping("/{id}/avatar")
    @PreAuthorize("#id.toString() == authentication.name or hasAnyRole('SYSTEM_ADMIN', 'HR_MANAGER')")
    public ResponseEntity<EmployeeAvatarService.Profile> confirm(
            @PathVariable @Positive Long id, @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.confirmUpload(id, request.objectKey()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@employeeProfileAccess.canRead(authentication, #id)")
    public ResponseEntity<EmployeeAvatarService.Profile> profile(@PathVariable @Positive Long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(avatars.getProfile(id));
    }

    @GetMapping("/me")
    public ResponseEntity<EmployeeAvatarService.Profile> me(Authentication auth) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(avatars.getProfile(Long.valueOf(auth.getName())));
    }

    @PutMapping("/me")
    public ResponseEntity<EmployeeAvatarService.Profile> updateMe(
            Authentication auth, @Valid @RequestBody UpdateEmployeeProfileRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(profiles.update(Long.valueOf(auth.getName()), request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'HR_MANAGER')")
    public ResponseEntity<EmployeeAvatarService.Profile> update(
            @PathVariable @Positive Long id, @Valid @RequestBody UpdateEmployeeProfileRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.update(id, request));
    }
}
