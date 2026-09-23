package com.ems.identity_service.event;

public record DepartmentRenamedPayload(Long departmentId, String departmentName) {
    public static final String TYPE = "department.renamed";
}
