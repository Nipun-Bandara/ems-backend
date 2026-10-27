package com.ems.leave_service.controller;

import com.ems.leave_service.balance.LeaveBalanceService;
import com.ems.leave_service.balance.LeaveModels.*;
import com.ems.leave_service.balance.WorkingCalendar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/leaves")
public class LeaveBalanceController {
    private final LeaveBalanceService service;
    private final WorkingCalendar calendar;

    public LeaveBalanceController(LeaveBalanceService service, WorkingCalendar calendar) {
        this.service = service;
        this.calendar = calendar;
    }

    private static final String ADMIN = "hasAnyRole('HR_MANAGER','SYSTEM_ADMIN')";

    @GetMapping("/types")
    public List<Type> types() {
        return service.types();
    }

    @PostMapping("/types")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize(ADMIN)
    public Type createType(@RequestBody @Valid TypeInput input) {
        return service.saveType(null, input);
    }

    @PutMapping("/types/{id}")
    @PreAuthorize(ADMIN)
    public Type updateType(@PathVariable UUID id, @RequestBody @Valid TypeInput input) {
        return service.saveType(id, input);
    }

    @GetMapping("/balances/me")
    public List<Balance> mine(Authentication auth, @RequestParam(required = false) @Min(1900) @Max(9999) Integer year) {
        long employee = Long.parseLong(auth.getName());
        service.activeEmployee(employee);
        return service.balances(employee, year == null ? calendar.currentYear() : year);
    }

    @GetMapping("/entitlements")
    @PreAuthorize(ADMIN)
    public List<Balance> entitlements(
            @RequestParam @Positive long employeeId, @RequestParam @Min(1900) @Max(9999) int year) {
        return service.balances(employeeId, year);
    }

    @PostMapping("/entitlements")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    @PreAuthorize(ADMIN)
    public Balance allocate(Authentication auth, @RequestBody @Valid Allocation input) {
        return service.allocate(input, Long.parseLong(auth.getName()));
    }

    @PostMapping("/entitlements/{id}/adjustments")
    @PreAuthorize(ADMIN)
    public Entry adjust(Authentication auth, @PathVariable UUID id, @RequestBody @Valid Adjustment input) {
        return service.adjust(id, input, Long.parseLong(auth.getName()));
    }

    @GetMapping("/entitlements/{id}/entries")
    @PreAuthorize(ADMIN)
    public List<Entry> entries(@PathVariable UUID id) {
        return service.entries(id);
    }

    @GetMapping("/calendar")
    public WorkingCalendar.Policy policy() {
        return calendar.policy();
    }

    @GetMapping("/holidays")
    public List<WorkingCalendar.Holiday> holidays(@RequestParam @Min(1900) @Max(9999) int year) {
        return calendar.holidays(year);
    }

    @PutMapping("/holidays")
    @PreAuthorize(ADMIN)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void holiday(@RequestBody @Valid WorkingCalendar.Holiday input) {
        calendar.saveHoliday(input);
    }

    @DeleteMapping("/holidays/{date}")
    @PreAuthorize(ADMIN)
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void deleteHoliday(@PathVariable LocalDate date) {
        calendar.deleteHoliday(date);
    }
}
