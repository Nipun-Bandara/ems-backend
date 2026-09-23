package com.ems.org_service.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.dto.request.DesignationRequest;
import com.ems.org_service.dto.response.DesignationResponse;
import com.ems.org_service.entity.DesignationEntity;
import com.ems.org_service.event.DesignationCreatedPayload;
import com.ems.org_service.event.DesignationDeletedPayload;
import com.ems.org_service.event.DesignationUpdatedPayload;
import com.ems.org_service.repository.DepartmentRepository;
import com.ems.org_service.repository.DesignationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DesignationServiceImplTest {

    @Mock
    private DesignationRepository designations;

    @Mock
    private DepartmentRepository departments;

    @Mock
    private OutboxPublisher outbox;

    @Test
    void createPersistsDesignationAndWritesCreatedEvent() {
        DesignationRequest request = new DesignationRequest("Software Engineer", 4L, "Builds products");
        when(departments.existsById(4L)).thenReturn(true);
        when(designations.save(any(DesignationEntity.class))).thenAnswer(invocation -> {
            DesignationEntity saved = invocation.getArgument(0);
            saved.setId(9L);
            return saved;
        });

        DesignationResponse response = service().createDesignation(request);

        assertThat(response.id()).isEqualTo(9L);
        assertThat(response.name()).isEqualTo("Software Engineer");
        assertThat(response.departmentId()).isEqualTo(4L);
        verify(outbox)
                .publish(
                        eq("designation"),
                        eq("9"),
                        eq(DesignationCreatedPayload.TYPE),
                        any(DesignationCreatedPayload.class));
    }

    @Test
    void createRejectsAnUnknownDepartment() {
        DesignationRequest request = new DesignationRequest("Software Engineer", 99L, null);

        assertThatThrownBy(() -> service().createDesignation(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Department not found with ID: 99");
    }

    @Test
    void updateWritesUpdatedEvent() {
        DesignationEntity designation = DesignationEntity.builder()
                .id(9L)
                .name("Developer")
                .departmentId(4L)
                .createdAt(LocalDateTime.now())
                .build();
        when(designations.findById(9L)).thenReturn(Optional.of(designation));
        when(departments.existsById(7L)).thenReturn(true);
        when(designations.save(designation)).thenReturn(designation);

        DesignationResponse response =
                service().updateDesignation(9L, new DesignationRequest("Senior Developer", 7L, "Leads delivery"));

        assertThat(response.name()).isEqualTo("Senior Developer");
        assertThat(response.departmentId()).isEqualTo(7L);
        verify(outbox)
                .publish(
                        eq("designation"),
                        eq("9"),
                        eq(DesignationUpdatedPayload.TYPE),
                        eq(new DesignationUpdatedPayload(9L, "Senior Developer", 7L, "Leads delivery")));
    }

    @Test
    void deleteWritesDeletedEventWithDepartmentContext() {
        DesignationEntity designation = DesignationEntity.builder()
                .id(9L)
                .name("Software Engineer")
                .departmentId(4L)
                .createdAt(LocalDateTime.now())
                .build();
        when(designations.findById(9L)).thenReturn(Optional.of(designation));

        service().deleteDesignation(9L);

        verify(designations).delete(designation);
        verify(outbox)
                .publish(
                        eq("designation"),
                        eq("9"),
                        eq(DesignationDeletedPayload.TYPE),
                        eq(new DesignationDeletedPayload(9L, 4L)));
    }

    private DesignationServiceImpl service() {
        return new DesignationServiceImpl(designations, departments, outbox);
    }
}
