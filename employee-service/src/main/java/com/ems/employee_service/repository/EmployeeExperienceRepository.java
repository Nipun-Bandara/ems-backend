package com.ems.employee_service.repository;

import com.ems.employee_service.entity.EmployeeExperience;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeExperienceRepository extends JpaRepository<EmployeeExperience, Long> {
    List<EmployeeExperience> findByUserIdOrderByIdAsc(Long userId);

    Optional<EmployeeExperience> findByIdAndUserId(Long id, Long userId);
}
