package com.ems.org_service.service.impl;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.DesignationRequest;
import com.ems.org_service.dto.response.DesignationResponse;
import com.ems.org_service.dto.response.PaginatedDesignationResponse;
import com.ems.org_service.entity.DesignationEntity;
import com.ems.org_service.event.DesignationCreatedPayload;
import com.ems.org_service.event.DesignationDeletedPayload;
import com.ems.org_service.event.DesignationUpdatedPayload;
import com.ems.org_service.repository.DepartmentRepository;
import com.ems.org_service.repository.DesignationRepository;
import com.ems.org_service.service.DesignationService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class DesignationServiceImpl implements DesignationService {

    private final DesignationRepository designationRepository;
    private final DepartmentRepository departmentRepository;
    private final OutboxPublisher outboxPublisher;

    @Override
    public DesignationResponse createDesignation(DesignationRequest request) {
        validateDepartment(request.getDepartmentId());
        rejectDuplicateName(request.getDepartmentId(), request.getName(), null);

        DesignationEntity designation = DesignationEntity.builder()
                .name(request.getName().trim())
                .departmentId(request.getDepartmentId())
                .description(request.getDescription())
                .createdAt(LocalDateTime.now())
                .build();
        DesignationEntity saved = designationRepository.save(designation);
        outboxPublisher.publish(
                "designation",
                saved.getId().toString(),
                DesignationCreatedPayload.TYPE,
                new DesignationCreatedPayload(
                        saved.getId(),
                        saved.getName(),
                        saved.getDepartmentId(),
                        saved.getDescription(),
                        saved.getCreatedAt()));
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedDesignationResponse getDesignations(Long departmentId, int page, int limit) {
        PageRequest pageable = PageRequest.of(page, limit, Sort.by("id").ascending());
        Page<DesignationEntity> result = departmentId == null
                ? designationRepository.findAll(pageable)
                : designationRepository.findByDepartmentId(departmentId, pageable);
        return new PaginatedDesignationResponse(
                result.getContent().stream().map(this::toResponse).toList(), result.hasNext(), result.hasPrevious());
    }

    @Override
    @Transactional(readOnly = true)
    public DesignationResponse getDesignationById(Long id) {
        return toResponse(findDesignation(id));
    }

    @Override
    public DesignationResponse updateDesignation(Long id, DesignationRequest request) {
        DesignationEntity designation = findDesignation(id);
        validateDepartment(request.getDepartmentId());
        rejectDuplicateName(request.getDepartmentId(), request.getName(), id);

        designation.setName(request.getName().trim());
        designation.setDepartmentId(request.getDepartmentId());
        designation.setDescription(request.getDescription());
        DesignationEntity saved = designationRepository.save(designation);
        outboxPublisher.publish(
                "designation",
                saved.getId().toString(),
                DesignationUpdatedPayload.TYPE,
                new DesignationUpdatedPayload(
                        saved.getId(), saved.getName(), saved.getDepartmentId(), saved.getDescription()));
        return toResponse(saved);
    }

    @Override
    public void deleteDesignation(Long id) {
        DesignationEntity designation = findDesignation(id);
        designationRepository.delete(designation);
        outboxPublisher.publish(
                "designation",
                id.toString(),
                DesignationDeletedPayload.TYPE,
                new DesignationDeletedPayload(id, designation.getDepartmentId()));
    }

    private DesignationEntity findDesignation(Long id) {
        return designationRepository
                .findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Designation not found with ID: " + id));
    }

    private void validateDepartment(Long departmentId) {
        if (!departmentRepository.existsById(departmentId)) {
            throw new IllegalArgumentException("Department not found with ID: " + departmentId);
        }
    }

    private void rejectDuplicateName(Long departmentId, String name, Long excludedId) {
        String normalizedName = name.trim();
        boolean duplicate = excludedId == null
                ? designationRepository.existsByDepartmentIdAndNameIgnoreCase(departmentId, normalizedName)
                : designationRepository.existsByDepartmentIdAndNameIgnoreCaseAndIdNot(
                        departmentId, normalizedName, excludedId);
        if (duplicate) {
            throw new IllegalArgumentException(
                    "Designation with name '" + normalizedName + "' already exists in this department");
        }
    }

    private DesignationResponse toResponse(DesignationEntity designation) {
        return new DesignationResponse(
                designation.getId(),
                designation.getName(),
                designation.getDepartmentId(),
                designation.getDescription(),
                designation.getCreatedAt());
    }
}
