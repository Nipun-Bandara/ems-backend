package com.ems.org_service.event;

public record TeamCreatedPayload(Long teamId, String name, Long departmentId, Long leadUserId) {
    public static final String TYPE = "team.created";
}
