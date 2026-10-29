package com.ems.recruitment_service;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recruitment")
public class PingController {
    public record Ping(String service, String status, String userId) {}

    @GetMapping("/ping")
    public Ping ping(Authentication actor) {
        return new Ping("recruitment-service", "ok", actor.getName());
    }

    @GetMapping("/admin/ping")
    @PreAuthorize("hasAnyRole('HR_MANAGER', 'SYSTEM_ADMIN')")
    public Ping administration(Authentication actor) {
        return ping(actor);
    }
}
