package com.ems.org_service.repository;

import com.ems.org_service.entity.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, Long> {
    boolean existsByDepartmentName(String departmentName);
}
