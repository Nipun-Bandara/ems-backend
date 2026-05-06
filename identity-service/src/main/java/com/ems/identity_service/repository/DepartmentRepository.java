package com.ems.identity_service.repository;

import com.ems.identity_service.entity.DepartmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, Long> {
    Optional<DepartmentEntity> findByDepartmentName(String departmentName);
    boolean existsByDepartmentName(String departmentName);
}
