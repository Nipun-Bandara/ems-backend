package com.ems.org_service.dto.response;

import java.time.LocalDateTime;

public record DesignationResponse(
        Long id, String name, Long departmentId, String description, LocalDateTime createdAt) {}
