package com.ems.org_service.service.impl;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.org_service.entity.DepartmentEntity;
import com.ems.org_service.exception.ConflictException;
import com.ems.org_service.repository.DepartmentRepository;
import com.ems.org_service.repository.DesignationRepository;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DepartmentDesignationConstraintTest {

    @Mock
    private DepartmentRepository departments;

    @Mock
    private DesignationRepository designations;

    @Mock
    private OutboxPublisher outbox;

    @Test
    void refusesToDeleteDepartmentThatStillHasDesignations() {
        DepartmentEntity department = DepartmentEntity.builder()
                .departmentId(12L)
                .departmentName("Engineering")
                .createdAt(LocalDateTime.now())
                .build();
        when(departments.findById(12L)).thenReturn(Optional.of(department));
        when(designations.existsByDepartmentId(12L)).thenReturn(true);

        DepartmentServiceImpl service = new DepartmentServiceImpl(departments, designations, outbox);

        assertThatThrownBy(() -> service.deleteDepartment(12L))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Cannot delete department with ID 12 because it still has designations");
        verify(departments, never()).delete(department);
    }
}
