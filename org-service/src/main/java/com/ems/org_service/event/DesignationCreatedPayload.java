package com.ems.org_service.event;

import java.time.LocalDateTime;

public record DesignationCreatedPayload(
        Long id, String name, Long departmentId, String description, LocalDateTime createdAt) {
    public static final String TYPE = "designation.created";
}
