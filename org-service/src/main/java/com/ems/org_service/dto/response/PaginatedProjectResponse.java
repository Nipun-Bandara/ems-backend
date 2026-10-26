package com.ems.org_service.dto.response;

import java.util.List;

public record PaginatedProjectResponse(List<ProjectResponse> projects, boolean hasNext, boolean hasPrevious) {}
