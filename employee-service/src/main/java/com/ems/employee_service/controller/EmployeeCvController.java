package com.ems.employee_service.controller;

import com.ems.employee_service.service.EmployeeCvService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/employees/{userId}/cv")
@PreAuthorize("#userId.toString() == authentication.name or hasAnyRole('SYSTEM_ADMIN', 'HR_MANAGER')")
public class EmployeeCvController {
    private final EmployeeCvService cvs;

    public record UploadRequest(
            @NotBlank @Size(max = 100) String contentType,
            @Positive @Max(10485760) long size) {}

    public record ConfirmRequest(@NotBlank @Size(max = 255) String objectKey) {}

    @PostMapping("/upload-url")
    public ResponseEntity<EmployeeCvService.Upload> upload(
            @PathVariable @Positive Long userId, @Valid @RequestBody UploadRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(cvs.requestUpload(userId, request.contentType(), request.size()));
    }

    @PutMapping
    public ResponseEntity<EmployeeCvService.Cv> confirm(
            @PathVariable @Positive Long userId, @Valid @RequestBody ConfirmRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(cvs.confirmUpload(userId, request.objectKey()));
    }

    @GetMapping
    public ResponseEntity<EmployeeCvService.Cv> download(@PathVariable @Positive Long userId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cvs.getCv(userId));
    }
}
