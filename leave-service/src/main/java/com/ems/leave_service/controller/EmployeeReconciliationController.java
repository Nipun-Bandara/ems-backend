package com.ems.leave_service.controller;

import com.ems.leave_service.projection.EmployeeReconciliationService;
import com.ems.leave_service.projection.EmployeeSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leaves/admin/employees")
public class EmployeeReconciliationController {
    private final EmployeeReconciliationService service;

    public EmployeeReconciliationController(EmployeeReconciliationService service) {
        this.service = service;
    }

    @PutMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public void reconcile(@PathVariable @Positive long userId, @RequestBody @Valid EmployeeSnapshot snapshot) {
        service.reconcile(userId, snapshot);
    }
}
