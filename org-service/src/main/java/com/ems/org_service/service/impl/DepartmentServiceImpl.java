package com.ems.org_service.service.impl;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.CreateDepartmentRequest;
import com.ems.org_service.dto.response.DepartmentResponse;
import com.ems.org_service.entity.DepartmentEntity;
import com.ems.org_service.event.DepartmentCreatedPayload;
import com.ems.org_service.event.DepartmentDeletedPayload;
import com.ems.org_service.event.DepartmentRenamedPayload;
import com.ems.org_service.exception.ConflictException;
import com.ems.org_service.repository.DepartmentRepository;
import com.ems.org_service.repository.DesignationRepository;
import com.ems.org_service.service.DepartmentService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DepartmentServiceImpl implements DepartmentService {

    private final DepartmentRepository departmentRepository;
    private final DesignationRepository designationRepository;
    private final OutboxPublisher outboxPublisher;

    @Override
    public DepartmentResponse createDepartment(CreateDepartmentRequest request) {
        rejectDuplicateName(request.getDepartmentName());

        DepartmentEntity department = DepartmentEntity.builder()
                .departmentName(request.getDepartmentName())
                .description(request.getDescription())
                .createdAt(LocalDateTime.now())
                .build();
        DepartmentEntity savedDepartment = departmentRepository.save(department);
        outboxPublisher.publish(
                "department",
                savedDepartment.getDepartmentId().toString(),
                DepartmentCreatedPayload.TYPE,
                new DepartmentCreatedPayload(savedDepartment.getDepartmentId(), savedDepartment.getDepartmentName()));
        return convertToDepartmentResponse(savedDepartment);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAllDepartments() {
        return departmentRepository.findAll().stream()
                .map(this::convertToDepartmentResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DepartmentResponse getDepartmentById(Long departmentId) {
        return convertToDepartmentResponse(findDepartment(departmentId));
    }

    @Override
    public DepartmentResponse updateDepartment(Long departmentId, CreateDepartmentRequest request) {
        DepartmentEntity department = findDepartment(departmentId);
        boolean renamed = !department.getDepartmentName().equals(request.getDepartmentName());
        if (renamed) {
            rejectDuplicateName(request.getDepartmentName());
        }
        department.setDepartmentName(request.getDepartmentName());
        department.setDescription(request.getDescription());
        DepartmentEntity savedDepartment = departmentRepository.save(department);
        if (renamed) {
            outboxPublisher.publish(
                    "department",
                    departmentId.toString(),
                    DepartmentRenamedPayload.TYPE,
                    new DepartmentRenamedPayload(departmentId, savedDepartment.getDepartmentName()));
        }
        return convertToDepartmentResponse(savedDepartment);
    }

    @Override
    public void deleteDepartment(Long departmentId) {
        DepartmentEntity department = findDepartment(departmentId);
        if (designationRepository.existsByDepartmentId(departmentId)) {
            throw new ConflictException(
                    "Cannot delete department with ID " + departmentId + " because it still has designations");
        }
        departmentRepository.delete(department);
        outboxPublisher.publish(
                "department",
                departmentId.toString(),
                DepartmentDeletedPayload.TYPE,
                new DepartmentDeletedPayload(departmentId));
    }

    private DepartmentEntity findDepartment(Long departmentId) {
        return departmentRepository
                .findById(departmentId)
                .orElseThrow(() -> new IllegalArgumentException("Department not found with ID: " + departmentId));
    }

    private void rejectDuplicateName(String departmentName) {
        if (departmentRepository.existsByDepartmentName(departmentName)) {
            throw new IllegalArgumentException("Department with name '" + departmentName + "' already exists");
        }
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
