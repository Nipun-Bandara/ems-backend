package com.ems.org_service.event;

import com.ems.org_service.dto.response.ProjectResponse;

public record ProjectCreatedPayload(ProjectResponse project) {
    public static final String TYPE = "project.created";
}
