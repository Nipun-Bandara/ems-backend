package com.ems.org_service.dto.response;

import java.util.List;

public record PaginatedDesignationResponse(
        List<DesignationResponse> designations, boolean hasNext, boolean hasPrevious) {}
