package com.ems.claim_service;

import static com.ems.claim_service.Models.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/claims")
public class ClaimController {
    private final ClaimService service;
    private final EmployeeScopeStore scope;

    public ClaimController(ClaimService service, EmployeeScopeStore scope) {
        this.service = service;
        this.scope = scope;
    }

    private long actor(Authentication a) {
        return Long.parseLong(a.getName());
    }

    @GetMapping("/permissions")
    public Permissions permissions(Authentication a) {
        return service.permissions(actor(a));
    }

    @GetMapping("/me")
    public List<Claim> mine(Authentication a) {
        return service.mine(actor(a));
    }

    @PostMapping
    public Claim create(Authentication a, @Valid @RequestBody Create input) {
        return service.create(actor(a), input);
    }

    @GetMapping("/{id}")
    public Claim get(Authentication a, @PathVariable UUID id) {
        return service.get(id, actor(a));
    }

    @PutMapping("/{id}")
    public Claim save(Authentication a, @PathVariable UUID id, @Valid @RequestBody Save input) {
        return service.save(id, actor(a), input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(Authentication a, @PathVariable UUID id, @RequestParam @Min(0) long version) {
        service.delete(id, actor(a), version);
    }

    @PostMapping("/{id}/submit")
    public Claim submit(Authentication a, @PathVariable UUID id, @Valid @RequestBody Transition input) {
        return service.submit(id, actor(a), input);
    }

    @PostMapping("/{id}/withdraw")
    public Claim withdraw(Authentication a, @PathVariable UUID id, @Valid @RequestBody Transition input) {
        return service.withdraw(id, actor(a), input);
    }

    @GetMapping("/reviewer/inbox")
    public List<Claim> inbox(Authentication a) {
        return service.inbox(actor(a));
    }

    @GetMapping("/reviewer/{id}")
    public Claim detail(Authentication a, @PathVariable UUID id) {
        return service.detail(id, actor(a));
    }

    @PostMapping("/{id}/approve")
    public Claim approve(Authentication a, @PathVariable UUID id, @Valid @RequestBody Transition input) {
        return service.decide(id, actor(a), input, false);
    }

    @PostMapping("/{id}/reject")
    public Claim reject(Authentication a, @PathVariable UUID id, @Valid @RequestBody Transition input) {
        return service.decide(id, actor(a), input, true);
    }

    @PutMapping("/admin/employees/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void employee(@PathVariable @Positive long userId, @Valid @RequestBody EmployeeScope input) {
        scope.reconcile(userId, input);
    }

    @PutMapping("/admin/reviewers/{userId}")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reviewer(@PathVariable @Positive long userId, @Valid @RequestBody Reviewer input) {
        scope.reviewer(userId, input);
    }
}
