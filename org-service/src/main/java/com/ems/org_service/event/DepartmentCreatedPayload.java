package com.ems.org_service.event;

public record DepartmentCreatedPayload(Long departmentId, String departmentName) {
    public static final String TYPE = "department.created";
}
