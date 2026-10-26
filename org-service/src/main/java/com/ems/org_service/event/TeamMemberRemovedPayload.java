package com.ems.org_service.event;

public record TeamMemberRemovedPayload(Long teamId, Long userId) {
    public static final String TYPE = "team.member.removed";
}
