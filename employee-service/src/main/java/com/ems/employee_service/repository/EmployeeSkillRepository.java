package com.ems.employee_service.repository;

import com.ems.employee_service.entity.EmployeeSkill;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, Long> {
    List<EmployeeSkill> findByUserIdOrderByIdAsc(Long userId);

    Optional<EmployeeSkill> findByIdAndUserId(Long id, Long userId);
}
