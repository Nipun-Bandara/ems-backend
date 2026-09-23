package com.ems.org_service.service;

import com.ems.org_service.dto.request.CreateDepartmentRequest;
import com.ems.org_service.dto.response.DepartmentResponse;
import java.util.List;

public interface DepartmentService {
    DepartmentResponse createDepartment(CreateDepartmentRequest request);

    List<DepartmentResponse> getAllDepartments();

    DepartmentResponse getDepartmentById(Long departmentId);

    DepartmentResponse updateDepartment(Long departmentId, CreateDepartmentRequest request);

    void deleteDepartment(Long departmentId);
}
