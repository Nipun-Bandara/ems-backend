package com.ems.identity_service.messaging;

import com.ems.common.event.EventEnvelope;
import com.ems.common.outbox.IdempotentConsumer;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.event.DepartmentCreatedPayload;
import com.ems.identity_service.event.DepartmentDeletedPayload;
import com.ems.identity_service.event.DepartmentRenamedPayload;
import com.ems.identity_service.projection.DepartmentReplicaEntity;
import com.ems.identity_service.projection.DepartmentReplicaRepository;
import com.ems.identity_service.repository.UserRepository;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class DepartmentProjectionHandler {

    private final DepartmentReplicaRepository departments;
    private final UserRepository users;
    private final JsonMapper jsonMapper;

    public DepartmentProjectionHandler(
            DepartmentReplicaRepository departments, UserRepository users, JsonMapper jsonMapper) {
        this.departments = departments;
        this.users = users;
        this.jsonMapper = jsonMapper;
    }

    @IdempotentConsumer("identity.department-created")
    @Transactional
    public void onCreated(EventEnvelope<JsonNode> event) {
        DepartmentCreatedPayload payload = jsonMapper.treeToValue(event.payload(), DepartmentCreatedPayload.class);
        departments.save(new DepartmentReplicaEntity(payload.departmentId(), payload.departmentName()));
    }

    @IdempotentConsumer("identity.department-renamed")
    @Transactional
    public void onRenamed(EventEnvelope<JsonNode> event) {
        DepartmentRenamedPayload payload = jsonMapper.treeToValue(event.payload(), DepartmentRenamedPayload.class);
        DepartmentReplicaEntity department = departments
                .findById(payload.departmentId())
                .orElseGet(() -> new DepartmentReplicaEntity(payload.departmentId(), payload.departmentName()));
        department.setDepartmentName(payload.departmentName());
        departments.save(department);

        List<UserEntity> affectedUsers = users.findByDepartmentId(payload.departmentId());
        affectedUsers.forEach(user -> user.setDepartmentName(payload.departmentName()));
        users.saveAll(affectedUsers);
    }

    @IdempotentConsumer("identity.department-deleted")
    @Transactional
    public void onDeleted(EventEnvelope<JsonNode> event) {
        DepartmentDeletedPayload payload = jsonMapper.treeToValue(event.payload(), DepartmentDeletedPayload.class);
        List<UserEntity> affectedUsers = users.findByDepartmentId(payload.departmentId());
        affectedUsers.forEach(user -> {
            user.setDepartmentId(null);
            user.setDepartmentName(null);
            user.setIsAssigned(false);
        });
        users.saveAll(affectedUsers);
        departments.deleteById(payload.departmentId());
    }
}
