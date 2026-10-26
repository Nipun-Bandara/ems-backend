package com.ems.org_service.entity;

import java.io.Serializable;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeamMemberId implements Serializable {
    private Long teamId;
    private Long userId;
}
