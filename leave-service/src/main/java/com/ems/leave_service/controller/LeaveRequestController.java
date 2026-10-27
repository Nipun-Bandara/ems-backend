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

    public LeaveRequestController(LeaveRequestService service) {
        this.service = service;
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

    @PostMapping("/requests/{id}/reject")
    public LeaveRequest reject(Authentication a, @PathVariable UUID id, @RequestBody @Valid Action input) {
        return service.finish(id, actor(a), input, true);
    }

    @PostMapping("/requests/{id}/cancel")
    public LeaveRequest cancel(Authentication a, @PathVariable UUID id, @RequestBody @Valid Action input) {
        return service.finish(id, actor(a), input, false);
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
