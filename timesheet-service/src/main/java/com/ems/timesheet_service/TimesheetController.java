package com.ems.timesheet_service;

import static com.ems.timesheet_service.Models.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/timesheets")
public class TimesheetController {
    private final TimesheetService service;
    private final ProjectionStore projections;
    private final OrganizationCalendar calendar;
    private final ReviewScope scope;

    public TimesheetController(
            TimesheetService service, ProjectionStore projections, OrganizationCalendar calendar, ReviewScope scope) {
        this.service = service;
        this.projections = projections;
        this.calendar = calendar;
        this.scope = scope;
    }

    private long actor(Authentication a) {
        return Long.parseLong(a.getName());
    }

    @GetMapping("/calendar")
    public Calendar calendar() {
        return calendar.current();
    }

    @GetMapping("/projects")
    public List<Project> projects(Authentication a, @RequestParam LocalDate weekStart) {
        return projections.projects(actor(a), weekStart);
    }

    @GetMapping("/me")
    public List<Sheet> mine(Authentication a, @RequestParam LocalDate weekStart) {
        return service.mine(actor(a), weekStart);
    }

    @PostMapping
    public Sheet create(Authentication a, @Valid @RequestBody Create input) {
        return service.create(actor(a), input);
    }

    @GetMapping("/{id}")
    public Sheet get(Authentication a, @PathVariable UUID id) {
        return service.get(id, actor(a));
    }

    @PutMapping("/{id}")
    public Sheet save(Authentication a, @PathVariable UUID id, @Valid @RequestBody Save input) {
        return service.save(id, actor(a), input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication a, @PathVariable UUID id, @RequestParam @Min(0) long version) {
        service.delete(id, actor(a), version);
    }

    @PostMapping("/{id}/submit")
    public Sheet submit(Authentication a, @PathVariable UUID id, @Valid @RequestBody Submit input) {
        return service.submit(id, actor(a), input);
    }

    @GetMapping("/permissions")
    public Permissions permissions(Authentication a) {
        return new Permissions(scope.reviewer(actor(a)), scope.active(actor(a)));
    }

    @GetMapping("/reviewer/inbox")
    public List<Sheet> inbox(Authentication a) {
        return service.inbox(actor(a));
    }

    @GetMapping("/reviewer/{id}")
    public Sheet reviewDetail(Authentication a, @PathVariable UUID id) {
        return service.reviewDetail(id, actor(a));
    }

    @PostMapping("/{id}/approve")
    public Sheet approve(Authentication a, @PathVariable UUID id, @Valid @RequestBody Review input) {
        return service.decide(id, actor(a), input, false);
    }

    @PostMapping("/{id}/reject")
    public Sheet reject(Authentication a, @PathVariable UUID id, @Valid @RequestBody Review input) {
        return service.decide(id, actor(a), input, true);
    }

    @PostMapping("/{id}/reopen")
    public Sheet reopen(Authentication a, @PathVariable UUID id, @Valid @RequestBody Submit input) {
        return service.reopen(id, actor(a), input);
    }

    @GetMapping("/summary")
    public List<Summary> summary(
            Authentication a,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(required = false) Long employeeId,
            @RequestParam(required = false) Long departmentId) {
        return service.summary(actor(a), from, to, employeeId, departmentId);
    }

    @PutMapping("/admin/employees/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void employee(
            @PathVariable @jakarta.validation.constraints.Positive long userId,
            @Valid @RequestBody EmployeeScope input) {
        scope.reconcile(userId, input);
    }

    @PutMapping("/admin/reviewers/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reviewer(
            @PathVariable @jakarta.validation.constraints.Positive long userId, @Valid @RequestBody Reviewer input) {
        scope.reviewer(userId, input);
    }

    @PutMapping("/admin/projections")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void backfill(Authentication a, @Valid @RequestBody Backfill input) {
        projections.backfill(actor(a), input);
    }
}
