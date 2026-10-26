package com.ems.org_service.repository;

import com.ems.org_service.entity.TeamEntity;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TeamRepository extends JpaRepository<TeamEntity, Long> {
    List<TeamEntity> findAllByOrderByIdAsc();

    List<TeamEntity> findByDepartmentIdOrderByIdAsc(Long departmentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TeamEntity t where t.id = :id")
    Optional<TeamEntity> findForUpdate(@Param("id") Long id);
}
