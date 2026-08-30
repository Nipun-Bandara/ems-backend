package com.ems.identity_service.repository;

import com.ems.identity_service.entity.UserEntity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<UserEntity, Long> {
    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    boolean existsByEmail(String email);

    Page<UserEntity> findByIsAssignedTrue(Pageable pageable);

    Page<UserEntity> findByIsAssignedFalse(Pageable pageable);
}
