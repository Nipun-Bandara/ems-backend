package com.ems.identity_service.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserEntity implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "email", nullable = false, unique = true)
    private String email;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "is_banned", nullable = false)
    @Builder.Default
    private Boolean isBanned = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /**
     * When the account's email address was proved to be reachable, or null if it has not
     * been. A timestamp rather than a boolean because the answer to "is this account
     * verified" is the cheap half of what this column is asked; "since when" is the half a
     * flag throws away and nothing can reconstruct.
     */
    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    /**
     * The moment before which a token issued for this account is no longer honoured, or null
     * if nothing has ever been revoked for it.
     *
     * <p>Refresh tokens are stateless JWTs — nothing records that one was handed out, so a
     * password reset has no rows to delete. This watermark is what makes them revocable
     * anyway: {@code refreshToken} compares a presented token's {@code iat} against it and
     * refuses anything older, which retires every session in one write.
     *
     * <p>It bounds refreshing, not access. An access token already issued stays valid until it
     * expires, because the gateway verifies signatures without a user lookup and checking this
     * would put a database read on every request. The short access token lifetime is what
     * closes that window.
     */
    @Column(name = "tokens_valid_from")
    private Instant tokensValidFrom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private DepartmentEntity department;

    @Column(name = "is_assigned", nullable = false)
    @Builder.Default
    private Boolean isAssigned = false;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, fetch = FetchType.EAGER)
    private List<UserRoles> userRoles;

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return userRoles != null
                ? userRoles.stream()
                        .map(ur -> new SimpleGrantedAuthority(
                                "ROLE_" + ur.getRole().getRoleName().name()))
                        .toList()
                : List.of();
    }

    @Override
    public boolean isAccountNonLocked() {
        return !isBanned;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return !isBanned;
    }
}
