package com.ems.org_service.repository;

import com.ems.org_service.entity.DesignationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DesignationRepository extends JpaRepository<DesignationEntity, Long> {
    Page<DesignationEntity> findByDepartmentId(Long departmentId, Pageable pageable);

    boolean existsByDepartmentId(Long departmentId);

    boolean existsByDepartmentIdAndNameIgnoreCase(Long departmentId, String name);

    boolean existsByDepartmentIdAndNameIgnoreCaseAndIdNot(Long departmentId, String name, Long id);
}
