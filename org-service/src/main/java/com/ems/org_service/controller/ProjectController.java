package com.ems.org_service.controller;

import com.ems.org_service.dto.request.ProjectRequest;
import com.ems.org_service.dto.response.*;
import com.ems.org_service.entity.ProjectStatus;
import com.ems.org_service.service.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
@Validated
public class ProjectController {
    private final ProjectService projects;

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(projects.create(request));
    }

    @GetMapping
    public PaginatedProjectResponse list(
            @RequestParam(required = false) @Positive Long teamId,
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return projects.list(teamId, status, page, limit, null);
    }

    @GetMapping("/my")
    public PaginatedProjectResponse my(
            Principal principal,
            @RequestParam(required = false) @Positive Long teamId,
            @RequestParam(required = false) ProjectStatus status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return projects.list(teamId, status, page, limit, Long.valueOf(principal.getName()));
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable @Positive Long id) {
        return projects.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ProjectResponse update(@PathVariable @Positive Long id, @Valid @RequestBody ProjectRequest request) {
        return projects.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
        projects.delete(id);
        return ResponseEntity.noContent().build();
    }
}
