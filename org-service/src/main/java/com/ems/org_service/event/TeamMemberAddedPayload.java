package com.ems.org_service.event;

import java.time.LocalDateTime;

public record TeamMemberAddedPayload(Long teamId, Long userId, LocalDateTime joinedAt) {
    public static final String TYPE = "team.member.added";
}
