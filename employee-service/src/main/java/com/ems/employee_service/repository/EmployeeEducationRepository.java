package com.ems.employee_service.repository;

import com.ems.employee_service.entity.EmployeeEducation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeEducationRepository extends JpaRepository<EmployeeEducation, Long> {
    List<EmployeeEducation> findByUserIdOrderByIdAsc(Long userId);

    Optional<EmployeeEducation> findByIdAndUserId(Long id, Long userId);
}
