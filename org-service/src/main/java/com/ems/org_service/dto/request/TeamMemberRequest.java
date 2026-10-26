package com.ems.org_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TeamMemberRequest(@NotNull @Positive Long userId) {}
