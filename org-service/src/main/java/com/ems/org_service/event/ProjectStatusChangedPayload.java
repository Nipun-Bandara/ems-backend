package com.ems.org_service.event;

import com.ems.org_service.entity.ProjectStatus;

public record ProjectStatusChangedPayload(Long id, Long teamId, ProjectStatus previousStatus, ProjectStatus status) {
    public static final String TYPE = "project.status-changed";
}
