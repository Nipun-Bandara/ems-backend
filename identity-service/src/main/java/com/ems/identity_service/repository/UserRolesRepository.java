package com.ems.identity_service.repository;

import com.ems.identity_service.entity.UserRoles;
import com.ems.identity_service.entity.UserRolesId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRolesRepository extends JpaRepository<UserRoles, UserRolesId> {
    List<UserRoles> findByUser_UserId(Long userId);
    
    @Query("SELECT ur FROM UserRoles ur WHERE ur.user.userId = :userId")
    List<UserRoles> getUserRoles(@Param("userId") Long userId);
}
