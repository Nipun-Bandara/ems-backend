package com.ems.employee_service.repository;

import com.ems.employee_service.entity.EmployeeProfile;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmployeeProfileRepository extends JpaRepository<EmployeeProfile, Long> {

    // Replays with a different event ID must not overwrite an existing profile either.
    @Modifying
    @Query(value = """
            INSERT INTO employee_profile (user_id, email, username, status, created_at)
            VALUES (:userId, :email, :username, 'ACTIVE', :createdAt)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    int createShell(
            @Param("userId") Long userId,
            @Param("email") String email,
            @Param("username") String username,
            @Param("createdAt") Instant createdAt);
}
