package com.ems.identity_service.repository;

import com.ems.identity_service.entity.DepartmentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<DepartmentEntity, Long> {
    Optional<DepartmentEntity> findByDepartmentName(String departmentName);

    boolean existsByDepartmentName(String departmentName);
}
