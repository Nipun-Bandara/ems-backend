package com.ems.org_service.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

@Entity
@Table(name = "team_members")
@IdClass(TeamMemberId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TeamMemberEntity {
    @Id
    @Column(name = "team_id", nullable = false)
    private Long teamId;
    // Identity-service owns users; deliberately no JPA association or cross-service FK.
    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private LocalDateTime joinedAt;
}
