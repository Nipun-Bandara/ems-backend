package com.ems.org_service.service;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.TeamRequest;
import com.ems.org_service.dto.response.TeamResponse;
import com.ems.org_service.entity.*;
import com.ems.org_service.event.*;
import com.ems.org_service.exception.TeamNotFoundException;
import com.ems.org_service.repository.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class TeamService {
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final DepartmentRepository departments;
    private final OutboxPublisher outbox;

    public TeamResponse create(TeamRequest request) {
        validateDepartment(request.departmentId());
        TeamEntity team = teams.save(TeamEntity.builder()
                .name(request.name().trim())
                .departmentId(request.departmentId())
                .leadUserId(request.leadUserId())
                .createdAt(LocalDateTime.now())
                .build());
        outbox.publish(
                "team",
                team.getId().toString(),
                TeamCreatedPayload.TYPE,
                new TeamCreatedPayload(team.getId(), team.getName(), team.getDepartmentId(), team.getLeadUserId()));
        return response(team, List.of());
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> list(Long departmentId) {
        List<TeamEntity> result = departmentId == null
                ? teams.findAllByOrderByIdAsc()
                : teams.findByDepartmentIdOrderByIdAsc(departmentId);
        if (result.isEmpty()) return List.of();
        Map<Long, List<TeamMemberEntity>> grouped =
                members
                        .findByTeamIdInOrderByUserIdAsc(
                                result.stream().map(TeamEntity::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(TeamMemberEntity::getTeamId));
        return result.stream()
                .map(t -> response(t, grouped.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamResponse get(Long id) {
        TeamEntity team = teams.findById(id).orElseThrow(() -> new TeamNotFoundException(id));
        return response(team, members.findByTeamIdOrderByUserIdAsc(id));
    }

    public TeamResponse update(Long id, TeamRequest request) {
        TeamEntity team = locked(id);
        validateDepartment(request.departmentId());
        team.setName(request.name().trim());
        team.setDepartmentId(request.departmentId());
        team.setLeadUserId(request.leadUserId());
        return response(teams.save(team), members.findByTeamIdOrderByUserIdAsc(id));
    }

    public void delete(Long id) {
        TeamEntity team = locked(id);
        List<TeamMemberEntity> existing = members.findByTeamIdOrderByUserIdAsc(id);
        members.deleteAll(existing);
        existing.forEach(member -> removed(id, member.getUserId()));
        // Flush child deletions before deleting the team; the database also cascades as a safeguard.
        members.flush();
        teams.delete(team);
    }

    public TeamResponse addMember(Long id, Long userId) {
        TeamEntity team = locked(id);
        TeamMemberId key = new TeamMemberId(id, userId);
        if (!members.existsById(key)) {
            TeamMemberEntity member = members.save(new TeamMemberEntity(id, userId, LocalDateTime.now()));
            outbox.publish(
                    "team",
                    id.toString(),
                    TeamMemberAddedPayload.TYPE,
                    new TeamMemberAddedPayload(id, userId, member.getJoinedAt()));
        }
        return response(team, members.findByTeamIdOrderByUserIdAsc(id));
    }

    public void removeMember(Long id, Long userId) {
        locked(id);
        members.findById(new TeamMemberId(id, userId)).ifPresent(member -> {
            members.delete(member);
            removed(id, userId);
        });
    }

    private void removed(Long id, Long userId) {
        outbox.publish("team", id.toString(), TeamMemberRemovedPayload.TYPE, new TeamMemberRemovedPayload(id, userId));
    }

    private TeamEntity locked(Long id) {
        return teams.findForUpdate(id).orElseThrow(() -> new TeamNotFoundException(id));
    }

    private void validateDepartment(Long id) {
        if (!departments.existsById(id)) throw new IllegalArgumentException("Department not found with ID: " + id);
        // No replicated users projection exists in org-service. Accept IDs for later reconciliation.
    }

    private TeamResponse response(TeamEntity team, List<TeamMemberEntity> membership) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getDepartmentId(),
                team.getLeadUserId(),
                team.getCreatedAt(),
                membership.stream()
                        .map(m -> new TeamResponse.Member(m.getUserId(), m.getJoinedAt()))
                        .toList());
    }
}
