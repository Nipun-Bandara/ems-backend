package com.ems.identity_service.event;

public record DepartmentDeletedPayload(Long departmentId) {
    public static final String TYPE = "department.deleted";
}
