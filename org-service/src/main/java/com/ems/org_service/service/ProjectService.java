package com.ems.org_service.service;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.ProjectRequest;
import com.ems.org_service.dto.response.*;
import com.ems.org_service.entity.*;
import com.ems.org_service.event.*;
import com.ems.org_service.exception.*;
import com.ems.org_service.repository.*;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class ProjectService {
    private final ProjectRepository projects;
    private final TeamRepository teams;
    private final OutboxPublisher outbox;

    public ProjectResponse create(ProjectRequest request) {
        validate(request);
        ProjectEntity project = new ProjectEntity();
        apply(project, request);
        project.setCreatedAt(LocalDateTime.now());
        ProjectResponse result = response(projects.save(project));
        outbox.publish(
                "project", result.id().toString(), ProjectCreatedPayload.TYPE, new ProjectCreatedPayload(result));
        return result;
    }

    @Transactional(readOnly = true)
    public PaginatedProjectResponse list(Long teamId, ProjectStatus status, int page, int limit, Long userId) {
        Specification<ProjectEntity> filter = (root, query, cb) -> {
            var predicate = cb.conjunction();
            if (teamId != null) predicate = cb.and(predicate, cb.equal(root.get("teamId"), teamId));
            if (status != null) predicate = cb.and(predicate, cb.equal(root.get("status"), status));
            if (userId != null) {
                var membership = query.subquery(Long.class);
                var member = membership.from(TeamMemberEntity.class);
                membership
                        .select(member.get("teamId"))
                        .where(
                                cb.equal(member.get("userId"), userId),
                                cb.equal(member.get("teamId"), root.get("teamId")));
                predicate = cb.and(predicate, cb.exists(membership));
            }
            return predicate;
        };
        Page<ProjectEntity> result = projects.findAll(
                filter, PageRequest.of(page, limit, Sort.by("id").ascending()));
        return new PaginatedProjectResponse(
                result.getContent().stream().map(this::response).toList(), result.hasNext(), result.hasPrevious());
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(Long id) {
        return response(projects.findById(id).orElseThrow(() -> new ProjectNotFoundException(id)));
    }

    public ProjectResponse update(Long id, ProjectRequest request) {
        ProjectEntity project = locked(id);
        validate(request);
        ProjectStatus previousStatus = project.getStatus();
        apply(project, request);
        ProjectResponse result = response(projects.save(project));
        outbox.publish("project", id.toString(), ProjectUpdatedPayload.TYPE, new ProjectUpdatedPayload(result));
        if (previousStatus != result.status()) {
            outbox.publish(
                    "project",
                    id.toString(),
                    ProjectStatusChangedPayload.TYPE,
                    new ProjectStatusChangedPayload(id, result.teamId(), previousStatus, result.status()));
        }
        return result;
    }

    public void delete(Long id) {
        projects.delete(locked(id));
    }

    private ProjectEntity locked(Long id) {
        return projects.findForUpdate(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    private void validate(ProjectRequest request) {
        if (!request.isDateRangeValid()) throw new IllegalArgumentException("End date must be on or after start date");
        if (!teams.existsById(request.teamId())) throw new TeamNotFoundException(request.teamId());
    }

    private void apply(ProjectEntity project, ProjectRequest request) {
        project.setName(request.name().trim());
        project.setDescription(request.description());
        project.setTeamId(request.teamId());
        project.setStatus(request.status());
        project.setStartDate(request.startDate());
        project.setEndDate(request.endDate());
    }

    private ProjectResponse response(ProjectEntity p) {
        return new ProjectResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getTeamId(),
                p.getStatus(),
                p.getStartDate(),
                p.getEndDate(),
                p.getCreatedAt());
    }
}
