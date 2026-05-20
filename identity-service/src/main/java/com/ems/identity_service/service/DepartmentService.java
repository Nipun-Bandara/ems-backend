package com.ems.identity_service.service;

import com.ems.identity_service.dto.request.CreateDepartmentRequest;
import com.ems.identity_service.dto.response.DepartmentResponse;

import java.util.List;

public interface DepartmentService {

    DepartmentResponse createDepartment(CreateDepartmentRequest request);

    List<DepartmentResponse> getAllDepartments();

    DepartmentResponse getDepartmentById(Long departmentId);
}
