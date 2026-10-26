package com.ems.org_service.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record TeamResponse(
        Long id, String name, Long departmentId, Long leadUserId, LocalDateTime createdAt, List<Member> members) {
    public record Member(Long userId, LocalDateTime joinedAt) {}
}
