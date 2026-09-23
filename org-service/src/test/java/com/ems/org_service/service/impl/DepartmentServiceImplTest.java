package com.ems.org_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.CreateDepartmentRequest;
import com.ems.org_service.dto.response.DepartmentResponse;
import com.ems.org_service.entity.DepartmentEntity;
import com.ems.org_service.event.DepartmentCreatedPayload;
import com.ems.org_service.event.DepartmentDeletedPayload;
import com.ems.org_service.repository.DepartmentRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepartmentServiceImplTest {

    @Mock
    private DepartmentRepository departments;

    @Mock
    private OutboxPublisher outbox;

    @Test
    void createKeepsTheApiShapeAndWritesTheEventToTheOutbox() {
        CreateDepartmentRequest request = CreateDepartmentRequest.builder()
                .departmentName("Engineering")
                .description("Builds EMS")
                .build();
        when(departments.save(org.mockito.ArgumentMatchers.any(DepartmentEntity.class)))
                .thenAnswer(invocation -> {
                    DepartmentEntity saved = invocation.getArgument(0);
                    saved.setDepartmentId(12L);
                    return saved;
                });

        DepartmentResponse response = new DepartmentServiceImpl(departments, outbox).createDepartment(request);

        assertThat(response.getDepartmentId()).isEqualTo(12L);
        assertThat(response.getDepartmentName()).isEqualTo("Engineering");
        assertThat(response.getDescription()).isEqualTo("Builds EMS");
        assertThat(response.getCreatedAt()).isNotNull();
        verify(outbox)
                .publish(
                        eq("department"),
                        eq("12"),
                        eq(DepartmentCreatedPayload.TYPE),
                        eq(new DepartmentCreatedPayload(12L, "Engineering")));
    }

    @Test
    void deleteWritesTheEventInTheSameServiceOperation() {
        DepartmentEntity department = DepartmentEntity.builder()
                .departmentId(12L)
                .departmentName("Engineering")
                .createdAt(LocalDateTime.now())
                .build();
        when(departments.findById(12L)).thenReturn(Optional.of(department));

        new DepartmentServiceImpl(departments, outbox).deleteDepartment(12L);

        verify(departments).delete(department);
        verify(outbox)
                .publish(
                        eq("department"),
                        eq("12"),
                        eq(DepartmentDeletedPayload.TYPE),
                        eq(new DepartmentDeletedPayload(12L)));
    }
}
