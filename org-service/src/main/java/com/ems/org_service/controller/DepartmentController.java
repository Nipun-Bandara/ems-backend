package com.ems.org_service.controller;

import com.ems.org_service.dto.request.CreateDepartmentRequest;
import com.ems.org_service.dto.response.DepartmentResponse;
import com.ems.org_service.service.DepartmentService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/departments")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;

    @PostMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DepartmentResponse> createDepartment(@Valid @RequestBody CreateDepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(departmentService.createDepartment(request));
    }

    @GetMapping
    public ResponseEntity<List<DepartmentResponse>> getAllDepartments() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(departmentService.getAllDepartments());
    }

    @GetMapping("/{departmentId}")
    public ResponseEntity<DepartmentResponse> getDepartmentById(@PathVariable Long departmentId) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(departmentService.getDepartmentById(departmentId));
    }

    @PutMapping("/{departmentId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<DepartmentResponse> updateDepartment(
            @PathVariable Long departmentId, @Valid @RequestBody CreateDepartmentRequest request) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(departmentService.updateDepartment(departmentId, request));
    }

    @DeleteMapping("/{departmentId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Void> deleteDepartment(@PathVariable Long departmentId) {
        departmentService.deleteDepartment(departmentId);
        return ResponseEntity.noContent().build();
    }
}
