package com.ems.identity_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.identity_service.dto.request.AssignRoleAndDepartmentRequest;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.event.UserAssignmentUpdatedPayload;
import com.ems.identity_service.projection.DepartmentReplicaEntity;
import com.ems.identity_service.projection.DepartmentReplicaRepository;
import com.ems.identity_service.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class UserServiceAssignmentTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void assigningDepartmentPublishesTheIdentitySnapshot() {
        var users = mock(UserRepository.class);
        var departments = mock(DepartmentReplicaRepository.class);
        var outbox = mock(OutboxPublisher.class);
        var service = new UserServiceImpl(
                outbox, users, mock(RoleRepository.class), mock(UserRolesRepository.class), departments);
        var target = UserEntity.builder()
                .userId(42L)
                .username("ada")
                .email("ada@ems.local")
                .build();
        when(users.findById(42L)).thenReturn(Optional.of(target));
        when(users.findById(1L))
                .thenReturn(Optional.of(UserEntity.builder().userId(1L).build()));
        when(users.save(target)).thenReturn(target);
        when(departments.findById(7L)).thenReturn(Optional.of(new DepartmentReplicaEntity(7L, "Engineering")));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(
                        "1", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        var request = new AssignRoleAndDepartmentRequest();
        request.setDepartmentId(7L);
        service.assignRoleAndDepartment(42L, request);
        var payload = ArgumentCaptor.forClass(UserAssignmentUpdatedPayload.class);
        verify(outbox).publish(eq("user"), eq("42"), eq("user.assignment.updated"), payload.capture());
        assertThat(payload.getValue().departmentId()).isEqualTo(7L);
        assertThat(payload.getValue().userId()).isEqualTo(42L);
        assertThat(payload.getValue().assignedAt()).isNotNull();
    }
}
