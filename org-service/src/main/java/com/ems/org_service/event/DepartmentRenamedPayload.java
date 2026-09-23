package com.ems.org_service.event;

public record DepartmentRenamedPayload(Long departmentId, String departmentName) {
    public static final String TYPE = "department.renamed";
}
