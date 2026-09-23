package com.ems.org_service.controller;

import com.ems.org_service.dto.request.DesignationRequest;
import com.ems.org_service.dto.response.DesignationResponse;
import com.ems.org_service.dto.response.PaginatedDesignationResponse;
import com.ems.org_service.service.DesignationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/designations")
@RequiredArgsConstructor
@Validated
public class DesignationController {

    private static final String CAN_WRITE = "hasRole('SYSTEM_ADMIN') or hasRole('DEPARTMENT_HEAD')";

    private final DesignationService designationService;

    @PostMapping
    @PreAuthorize(CAN_WRITE)
    public ResponseEntity<DesignationResponse> createDesignation(@Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(designationService.createDesignation(request));
    }

    @GetMapping
    public ResponseEntity<PaginatedDesignationResponse> getDesignations(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ResponseEntity.ok(designationService.getDesignations(departmentId, page, limit));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DesignationResponse> getDesignationById(@PathVariable Long id) {
        return ResponseEntity.ok(designationService.getDesignationById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(CAN_WRITE)
    public ResponseEntity<DesignationResponse> updateDesignation(
            @PathVariable Long id, @Valid @RequestBody DesignationRequest request) {
        return ResponseEntity.ok(designationService.updateDesignation(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(CAN_WRITE)
    public ResponseEntity<Void> deleteDesignation(@PathVariable Long id) {
        designationService.deleteDesignation(id);
        return ResponseEntity.noContent().build();
    }
}
