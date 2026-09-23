package com.ems.org_service.service;

import com.ems.org_service.dto.request.DesignationRequest;
import com.ems.org_service.dto.response.DesignationResponse;
import com.ems.org_service.dto.response.PaginatedDesignationResponse;

public interface DesignationService {
    DesignationResponse createDesignation(DesignationRequest request);

    PaginatedDesignationResponse getDesignations(Long departmentId, int page, int limit);

    DesignationResponse getDesignationById(Long id);

    DesignationResponse updateDesignation(Long id, DesignationRequest request);

    void deleteDesignation(Long id);
}
