package com.ems.org_service.repository;

import com.ems.org_service.entity.TeamMemberEntity;
import com.ems.org_service.entity.TeamMemberId;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberRepository extends JpaRepository<TeamMemberEntity, TeamMemberId> {
    List<TeamMemberEntity> findByTeamIdOrderByUserIdAsc(Long teamId);

    List<TeamMemberEntity> findByTeamIdInOrderByUserIdAsc(Collection<Long> teamIds);
}
