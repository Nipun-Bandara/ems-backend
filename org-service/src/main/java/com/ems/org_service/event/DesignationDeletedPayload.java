package com.ems.org_service.event;

public record DesignationDeletedPayload(Long id, Long departmentId) {
    public static final String TYPE = "designation.deleted";
}
