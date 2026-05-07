package com.ems.identity_service.service.impl;

import com.ems.identity_service.dto.request.CreateDepartmentRequest;
import com.ems.identity_service.dto.response.DepartmentResponse;
import com.ems.identity_service.entity.DepartmentEntity;
import com.ems.identity_service.repository.DepartmentRepository;
import com.ems.identity_service.service.DepartmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Override
    public DepartmentResponse createDepartment(CreateDepartmentRequest request) {
        if (departmentRepository.existsByDepartmentName(request.getDepartmentName())) {
            throw new IllegalArgumentException("Department with name '" + request.getDepartmentName() + "' already exists");
        }

        DepartmentEntity department = DepartmentEntity.builder()
                .departmentName(request.getDepartmentName())
                .description(request.getDescription())
                .createdAt(LocalDateTime.now())
                .build();

        DepartmentEntity savedDepartment = departmentRepository.save(department);
        return convertToDepartmentResponse(savedDepartment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAllDepartments() {
        List<DepartmentEntity> departments = departmentRepository.findAll();
        return departments.stream()
                .map(this::convertToDepartmentResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentResponse getDepartmentById(Long departmentId) {
        DepartmentEntity department = departmentRepository.findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + departmentId));
        return convertToDepartmentResponse(department);
    }

    private DepartmentResponse convertToDepartmentResponse(DepartmentEntity department) {
        return DepartmentResponse.builder()
                .departmentId(department.getDepartmentId())
                .departmentName(department.getDepartmentName())
                .description(department.getDescription())
                .createdAt(department.getCreatedAt())
                .build();
    }
}
