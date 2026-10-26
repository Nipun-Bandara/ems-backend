package com.ems.org_service.dto.response;

import com.ems.org_service.entity.ProjectStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record ProjectResponse(
        Long id,
        String name,
        String description,
        Long teamId,
        ProjectStatus status,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime createdAt) {}
