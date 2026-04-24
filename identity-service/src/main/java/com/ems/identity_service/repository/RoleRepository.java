package com.ems.identity_service.repository;

import com.ems.identity_service.entity.RoleEntity;
import com.ems.identity_service.enums.Role;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<RoleEntity, Long> {
    Optional<RoleEntity> findByRoleName(Role roleName);
}
