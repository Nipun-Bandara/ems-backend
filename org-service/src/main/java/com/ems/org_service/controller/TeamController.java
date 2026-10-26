package com.ems.org_service.controller;

import com.ems.org_service.dto.request.*;
import com.ems.org_service.dto.response.TeamResponse;
import com.ems.org_service.service.TeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
@Validated
public class TeamController {
    private final TeamService teams;

    @PostMapping
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ResponseEntity<TeamResponse> create(@Valid @RequestBody TeamRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(teams.create(request));
    }

    @GetMapping
    public List<TeamResponse> list(@RequestParam(required = false) @Positive Long departmentId) {
        return teams.list(departmentId);
    }

    @GetMapping("/{id}")
    public TeamResponse get(@PathVariable @Positive Long id) {
        return teams.get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public TeamResponse update(@PathVariable @Positive Long id, @Valid @RequestBody TeamRequest request) {
        return teams.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ResponseEntity<Void> delete(@PathVariable @Positive Long id) {
        teams.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public TeamResponse addMember(@PathVariable @Positive Long id, @Valid @RequestBody TeamMemberRequest request) {
        return teams.addMember(id, request.userId());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN', 'DEPARTMENT_HEAD')")
    public ResponseEntity<Void> removeMember(@PathVariable @Positive Long id, @PathVariable @Positive Long userId) {
        teams.removeMember(id, userId);
        return ResponseEntity.noContent().build();
    }
}
