package com.ems.org_service.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.TeamRequest;
import com.ems.org_service.entity.*;
import com.ems.org_service.event.*;
import com.ems.org_service.exception.TeamNotFoundException;
import com.ems.org_service.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {
    @Mock
    TeamRepository teams;

    @Mock
    TeamMemberRepository members;

    @Mock
    DepartmentRepository departments;

    @Mock
    OutboxPublisher outbox;

    TeamService service() {
        return new TeamService(teams, members, departments, outbox);
    }

    TeamEntity team() {
        return new TeamEntity(9L, "Platform", 4L, 900L, LocalDateTime.now());
    }

    @Test
    void createAcceptsExternalUserIdAndPublishesCreated() {
        when(departments.existsById(4L)).thenReturn(true);
        when(teams.save(any())).thenAnswer(call -> {
            TeamEntity team = call.getArgument(0);
            team.setId(9L);
            return team;
        });
        var result = service().create(new TeamRequest(" Platform ", 4L, 900L));
        assertThat(result.id()).isEqualTo(9L);
        assertThat(result.name()).isEqualTo("Platform");
        assertThat(result.leadUserId()).isEqualTo(900L);
        verify(outbox).publish("team", "9", TeamCreatedPayload.TYPE, new TeamCreatedPayload(9L, "Platform", 4L, 900L));
    }

    @Test
    void rejectsUnknownDepartmentAndMissingTeam() {
        assertThatThrownBy(() -> service().create(new TeamRequest("Platform", 4L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service().get(9L)).isInstanceOf(TeamNotFoundException.class);
        verifyNoInteractions(outbox);
    }

    @Test
    void listFiltersAndIncludesMembership() {
        when(teams.findByDepartmentIdOrderByIdAsc(4L)).thenReturn(List.of(team()));
        var member = new TeamMemberEntity(9L, 42L, LocalDateTime.now());
        when(members.findByTeamIdInOrderByUserIdAsc(List.of(9L))).thenReturn(List.of(member));
        var result = service().list(4L);
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().members().getFirst().userId()).isEqualTo(42L);
        verify(teams, never()).findAllByOrderByIdAsc();
    }

    @Test
    void updateChangesDepartmentAndClearsLead() {
        when(teams.findForUpdate(9L)).thenReturn(Optional.of(team()));
        when(departments.existsById(7L)).thenReturn(true);
        when(teams.save(any())).thenAnswer(call -> call.getArgument(0));
        var result = service().update(9L, new TeamRequest("Delivery", 7L, null));
        assertThat(result.name()).isEqualTo("Delivery");
        assertThat(result.departmentId()).isEqualTo(7L);
        assertThat(result.leadUserId()).isNull();
    }

    @Test
    void addMembershipPublishesOnlyOnce() {
        when(teams.findForUpdate(9L)).thenReturn(Optional.of(team()));
        when(members.save(any())).thenAnswer(call -> call.getArgument(0));
        when(members.existsById(new TeamMemberId(9L, 42L))).thenReturn(false, true);
        service().addMember(9L, 42L);
        service().addMember(9L, 42L);
        verify(members, times(1)).save(any());
        verify(outbox, times(1))
                .publish(eq("team"), eq("9"), eq(TeamMemberAddedPayload.TYPE), any(TeamMemberAddedPayload.class));
    }

    @Test
    void removingMemberIsIdempotentAndPublishesRemoval() {
        when(teams.findForUpdate(9L)).thenReturn(Optional.of(team()));
        var member = new TeamMemberEntity(9L, 42L, LocalDateTime.now());
        when(members.findById(new TeamMemberId(9L, 42L))).thenReturn(Optional.of(member), Optional.empty());
        service().removeMember(9L, 42L);
        service().removeMember(9L, 42L);
        verify(members, times(1)).delete(member);
        verify(outbox, times(1))
                .publish("team", "9", TeamMemberRemovedPayload.TYPE, new TeamMemberRemovedPayload(9L, 42L));
    }

    @Test
    void deleteRemovesMembershipsAndPublishesRemovals() {
        var team = team();
        when(teams.findForUpdate(9L)).thenReturn(Optional.of(team));
        var member = new TeamMemberEntity(9L, 42L, LocalDateTime.now());
        when(members.findByTeamIdOrderByUserIdAsc(9L)).thenReturn(List.of(member));
        service().delete(9L);
        verify(members).deleteAll(List.of(member));
        verify(teams).delete(team);
        verify(outbox).publish("team", "9", TeamMemberRemovedPayload.TYPE, new TeamMemberRemovedPayload(9L, 42L));
    }
}
