package com.ems.timesheet_service;

import static com.ems.timesheet_service.AttendanceModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/timesheets/attendance")
public class AttendanceController {
    private final AttendanceService service;

    public AttendanceController(AttendanceService service) {
        this.service = service;
    }

    private long actor(Authentication a) {
        return Long.parseLong(a.getName());
    }

    @GetMapping("/current")
    public State current(Authentication a) {
        return service.current(actor(a));
    }

    @PostMapping("/clock-in")
    public Session clockIn(Authentication a, @Valid @RequestBody ClockIn input) {
        return service.clockIn(actor(a), input.requestId());
    }

    @PostMapping("/{id}/clock-out")
    public Session clockOut(Authentication a, @PathVariable UUID id) {
        return service.clockOut(actor(a), id);
    }

    @GetMapping("/history")
    public History history(
            Authentication a, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.history(actor(a), null, page, size);
    }

    @GetMapping("/hr/history")
    public History hrHistory(
            Authentication a,
            @RequestParam long employeeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!service.hr(actor(a)))
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "HR required");
        return service.history(actor(a), employeeId, page, size);
    }

    @PostMapping("/hr/{id}/corrections")
    public Session correct(Authentication a, @PathVariable UUID id, @Valid @RequestBody Correction input) {
        return service.correct(actor(a), id, input);
    }

    @GetMapping("/{id}/corrections")
    public List<Audit> audit(Authentication a, @PathVariable UUID id) {
        return service.audit(actor(a), id);
    }

    @GetMapping("/summary")
    public Summary summary(Authentication a, @RequestParam LocalDate from, @RequestParam LocalDate to) {
        return service.summary(actor(a), from, to);
    }
}
