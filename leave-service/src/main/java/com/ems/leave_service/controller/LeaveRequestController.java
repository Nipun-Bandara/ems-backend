package com.ems.leave_service.controller;

import com.ems.leave_service.request.LeaveRequestService;
import com.ems.leave_service.request.RequestModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leaves")
public class LeaveRequestController {
    private final LeaveRequestService service;
    private final com.ems.leave_service.request.LeaveWorkflowService workflows;

    public LeaveRequestController(
            LeaveRequestService service, com.ems.leave_service.request.LeaveWorkflowService workflows) {
        this.service = service;
        this.workflows = workflows;
    }

    private long actor(Authentication a) {
        return Long.parseLong(a.getName());
    }

    private boolean hr(Authentication a) {
        return a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_HR_MANAGER"));
    }

    @PostMapping("/requests/estimate")
    public Estimate estimate(@RequestBody @Valid EstimateInput input) {
        return service.estimate(input);
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    public LeaveRequest submit(Authentication a, @RequestBody @Valid Submit input) {
        return service.submit(actor(a), input);
    }

    @GetMapping("/requests/me")
    public List<LeaveRequest> mine(Authentication a) {
        return service.mine(actor(a));
    }

    @GetMapping("/requests/inbox")
    public List<LeaveRequest> inbox(Authentication a) {
        return service.inbox(actor(a), hr(a));
    }

    @GetMapping("/requests/{id}")
    public LeaveRequest get(Authentication a, @PathVariable UUID id) {
        return service.get(id, actor(a), hr(a));
    }

    @PostMapping("/requests/{id}/approve")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public com.ems.leave_service.request.WorkflowModels.Workflow approve(
            Authentication a,
            @PathVariable UUID id,
            @RequestBody @Valid com.ems.leave_service.request.WorkflowModels.Start input) {
        return workflows.start(id, actor(a), input, false);
    }

    @GetMapping("/requests/{id}/workflows")
    public List<com.ems.leave_service.request.WorkflowModels.Workflow> workflows(
            Authentication a, @PathVariable UUID id) {
        return workflows.forRequest(id, actor(a), hr(a));
    }

    @GetMapping("/workflows/{id}")
    public com.ems.leave_service.request.WorkflowModels.Workflow workflow(Authentication a, @PathVariable UUID id) {
        return workflows.get(id, actor(a), hr(a));
    }

    @PostMapping("/requests/{id}/reject")
    public LeaveRequest reject(Authentication a, @PathVariable UUID id, @RequestBody @Valid Action input) {
        return service.finish(id, actor(a), input, true);
    }

    @PostMapping("/requests/{id}/cancel")
    public Object cancel(Authentication a, @PathVariable UUID id, @RequestBody @Valid Action input) {
        var r = service.get(id, actor(a), hr(a));
        if (r.status() == Status.PENDING
                || (r.status() == Status.CANCELLED
                        && workflows.forRequest(id, actor(a), hr(a)).stream()
                                .noneMatch(w -> w.operation().equals("CANCEL"))))
            return service.finish(id, actor(a), input, false);
        if (input.version() == null)
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Version required for approved cancellation");
        return workflows.start(
                id,
                actor(a),
                new com.ems.leave_service.request.WorkflowModels.Start(
                        input.version(), input.idempotencyKey(), input.reason()),
                true);
    }

    @PostMapping("/requests/{id}/route")
    @PreAuthorize("hasRole('HR_MANAGER')")
    public LeaveRequest route(Authentication a, @PathVariable UUID id, @RequestBody @Valid Route input) {
        return service.route(id, actor(a), input);
    }

    @PutMapping("/admin/reviewers/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public void reviewer(@PathVariable @Positive long userId, @RequestBody @Valid ReviewerSnapshot input) {
        service.reviewer(userId, input);
    }
}
