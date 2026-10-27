package com.ems.employee_service.repository;

import com.ems.employee_service.entity.EmployeeProfile;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from EmployeeProfile p where p.userId = :id")
    java.util.Optional<EmployeeProfile> findForUpdate(@Param("id") Long id);

    // Replays with a different event ID must not overwrite an existing profile either.
    @Modifying
    @Query(value = """
            INSERT INTO employee_profile (user_id, email, username, status, created_at, department_id)
            VALUES (:userId, :email, :username, 'ACTIVE', :createdAt, (SELECT department_id FROM employee_assignment WHERE user_id = :userId))
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int createShell(
            @Param("userId") Long userId,
            @Param("email") String email,
            @Param("username") String username,
            @Param("createdAt") Instant createdAt);

    @Modifying
    @Query(value = """
            INSERT INTO employee_assignment (user_id, department_id, assigned_at)
            VALUES (:userId, :departmentId, :assignedAt)
            ON CONFLICT (user_id) DO UPDATE SET department_id = EXCLUDED.department_id, assigned_at = EXCLUDED.assigned_at
            WHERE employee_assignment.assigned_at < EXCLUDED.assigned_at
            """, nativeQuery = true)
    int replicateAssignment(
            @Param("userId") Long userId,
            @Param("departmentId") Long departmentId,
            @Param("assignedAt") Instant assignedAt);

    @Modifying
    @Query(value = """
            UPDATE employee_profile SET department_id = (SELECT department_id FROM employee_assignment WHERE user_id = :userId)
            WHERE user_id = :userId
            """, nativeQuery = true)
    int refreshDepartment(@Param("userId") Long userId);
}
