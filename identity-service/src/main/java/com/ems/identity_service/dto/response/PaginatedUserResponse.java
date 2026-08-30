package com.ems.identity_service.dto.response;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PaginatedUserResponse {
    private List<UserRecord> users;
    private boolean hasNext;
    private boolean hasPrevious;
}
