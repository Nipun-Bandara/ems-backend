package com.ems.org_service.event;

import com.ems.org_service.dto.response.ProjectResponse;

public record ProjectUpdatedPayload(ProjectResponse project) {
    public static final String TYPE = "project.updated";
}
