package com.ems.identity_service.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.event.EventEnvelope;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.event.DepartmentCreatedPayload;
import com.ems.identity_service.event.DepartmentDeletedPayload;
import com.ems.identity_service.event.DepartmentRenamedPayload;
import com.ems.identity_service.projection.DepartmentReplicaEntity;
import com.ems.identity_service.projection.DepartmentReplicaRepository;
import com.ems.identity_service.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DepartmentProjectionHandlerTest {

    @Mock
    private DepartmentReplicaRepository departments;

    @Mock
    private UserRepository users;

    private JsonMapper jsonMapper;
    private DepartmentProjectionHandler handler;

    @BeforeEach
    void setUp() {
        jsonMapper = JsonMapper.builder().build();
        handler = new DepartmentProjectionHandler(departments, users, jsonMapper);
    }

    @Test
    void createsTheLocalAssignmentProjection() {
        handler.onCreated(envelope(DepartmentCreatedPayload.TYPE, new DepartmentCreatedPayload(12L, "Engineering")));

        ArgumentCaptor<DepartmentReplicaEntity> saved = ArgumentCaptor.forClass(DepartmentReplicaEntity.class);
        verify(departments).save(saved.capture());
        assertThat(saved.getValue().getDepartmentId()).isEqualTo(12L);
        assertThat(saved.getValue().getDepartmentName()).isEqualTo("Engineering");
    }

    @Test
    void renamingUpdatesTheProjectionAndEveryAssignedUser() {
        DepartmentReplicaEntity replica = new DepartmentReplicaEntity(12L, "Engineering");
        UserEntity first = UserEntity.builder()
                .departmentId(12L)
                .departmentName("Engineering")
                .build();
        UserEntity second = UserEntity.builder()
                .departmentId(12L)
                .departmentName("Engineering")
                .build();
        when(departments.findById(12L)).thenReturn(Optional.of(replica));
        when(users.findByDepartmentId(12L)).thenReturn(List.of(first, second));

        handler.onRenamed(envelope(DepartmentRenamedPayload.TYPE, new DepartmentRenamedPayload(12L, "Platform")));

        assertThat(replica.getDepartmentName()).isEqualTo("Platform");
        assertThat(List.of(first.getDepartmentName(), second.getDepartmentName()))
                .containsOnly("Platform");
        verify(users).saveAll(any());
    }

    @Test
    void deletingNullsDepartmentDataWithoutCallingOrgService() {
        UserEntity user = UserEntity.builder()
                .departmentId(12L)
                .departmentName("Engineering")
                .isAssigned(true)
                .build();
        when(users.findByDepartmentId(12L)).thenReturn(List.of(user));

        handler.onDeleted(envelope(DepartmentDeletedPayload.TYPE, new DepartmentDeletedPayload(12L)));

        assertThat(user.getDepartmentId()).isNull();
        assertThat(user.getDepartmentName()).isNull();
        assertThat(user.getIsAssigned()).isFalse();
        verify(departments).deleteById(12L);
    }

    private EventEnvelope<JsonNode> envelope(String type, Object payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), type, Instant.now(), "test-correlation-id", jsonMapper.valueToTree(payload));
    }
}
