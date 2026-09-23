package com.ems.org_service.event;

public record DesignationUpdatedPayload(Long id, String name, Long departmentId, String description) {
    public static final String TYPE = "designation.updated";
}
