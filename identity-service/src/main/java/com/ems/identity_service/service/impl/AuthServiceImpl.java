package com.ems.identity_service.service.impl;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RefreshTokenRequest;
import com.ems.identity_service.dto.request.RegisterRequest;
import com.ems.identity_service.dto.request.ResendVerificationRequest;
import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.dto.response.TokenValidationResponse;
import com.ems.identity_service.dto.response.VerificationResponse;
import com.ems.identity_service.entity.RoleEntity;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.entity.UserRoles;
import com.ems.identity_service.entity.VerificationToken;
import com.ems.identity_service.event.UserRegisteredPayload;
import com.ems.identity_service.event.UserVerifiedPayload;
import com.ems.identity_service.exception.AccountBannedException;
import com.ems.identity_service.exception.EmailNotVerifiedException;
import com.ems.identity_service.exception.InvalidTokenException;
import com.ems.identity_service.exception.InvalidVerificationTokenException;
import com.ems.identity_service.exception.ResendTooSoonException;
import com.ems.identity_service.repository.RoleRepository;
import com.ems.identity_service.repository.UserRepository;
import com.ems.identity_service.repository.UserRolesRepository;
import com.ems.identity_service.repository.VerificationTokenRepository;
import com.ems.identity_service.security.AuthenticatedUser;
import com.ems.identity_service.security.JwtService;
import com.ems.identity_service.service.AuthService;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /**
     * How long an address must wait between verification mails. Short enough that a user who
     * genuinely did not get the first one is not stuck, long enough that the endpoint cannot
     * be turned into a way of mailing somebody repeatedly.
     */
    private static final Duration RESEND_COOLDOWN = Duration.ofMinutes(1);

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRolesRepository userRolesRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final OutboxPublisher outboxPublisher;

    @Override
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("Email already in use");
        }

        // Create new user
        UserEntity user = UserEntity.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .isBanned(false)
                .createdAt(LocalDateTime.now())
                .build();

        UserEntity savedUser = userRepository.save(user);

        // Assign roles if provided
        if (request.getRoles() != null && !request.getRoles().isEmpty()) {
            List<UserRoles> userRoles = request.getRoles().stream()
                    .map(roleName -> {
                        RoleEntity role = roleRepository
                                .findByRoleName(roleName)
                                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleName));
                        return UserRoles.builder()
                                .user(savedUser)
                                .role(role)
                                .assignedAt(LocalDateTime.now())
                                .build();
                    })
                    .collect(Collectors.toList());

            userRolesRepository.saveAll(userRoles);
            savedUser.setUserRoles(userRoles);
        }

        // Both the token row and the event are written inside this method's transaction on
        // purpose: they are committed with the user or not at all, so the broker being down
        // cannot cost us a registration and a crash here cannot leave an account nobody was
        // told about. OutboxPoller does the actual publishing, once the row is committed.
        publishRegistration(savedUser);

        // No access token. The account cannot be signed in to until the address is verified,
        // and handing out a session here would be a way around the check login performs --
        // the caller is told to go and read their mail instead.
        return AuthResponse.builder()
                .userId(savedUser.getUserId())
                .email(savedUser.getEmail())
                .username(savedUser.getUsername())
                .departmentId(
                        savedUser.getDepartment() != null
                                ? savedUser.getDepartment().getDepartmentId()
                                : null)
                .departmentName(
                        savedUser.getDepartment() != null
                                ? savedUser.getDepartment().getDepartmentName()
                                : null)
                .roles(
                        savedUser.getUserRoles() != null
                                ? savedUser.getUserRoles().stream()
                                        .map(ur -> ur.getRole().getRoleName())
                                        .collect(Collectors.toList())
                                : List.of())
                .isBanned(savedUser.getIsBanned())
                .emailVerified(false)
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest request) {
        UserEntity user = userRepository
                .findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(user.getUsername(), request.getPassword()));
        } catch (AuthenticationException e) {
            throw new IllegalArgumentException("Email or password is incorrect");
        }

        // Check if user is banned
        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        // After the password check, never before it: reaching this branch means the caller
        // already proved the account is theirs, so telling them it is unverified gives away
        // nothing they did not already know. Asking first would turn login into a way of
        // testing which addresses are registered.
        if (user.getEmailVerifiedAt() == null) {
            throw new EmailNotVerifiedException(
                    "Verify your email address before signing in. Check your inbox for the link.");
        }

        // Load user roles
        List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());
        user.setUserRoles(userRoles);

        String token = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return AuthResponse.builder()
                .token(token)
                .refreshToken(refreshToken)
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .departmentId(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null)
                .departmentName(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentName() : null)
                .roles(userRoles.stream().map(ur -> ur.getRole().getRoleName()).collect(Collectors.toList()))
                .isBanned(user.getIsBanned())
                .emailVerified(true)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse getCurrentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Long userId = AuthenticatedUser.requireUserId(auth);

        UserEntity user =
                userRepository.findById(userId).orElseThrow(() -> new UsernameNotFoundException("User not found"));

        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        // Load user roles
        List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());

        return AuthResponse.builder()
                .userId(user.getUserId())
                .email(user.getEmail())
                .username(user.getUsername())
                .departmentId(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentId() : null)
                .departmentName(
                        user.getDepartment() != null ? user.getDepartment().getDepartmentName() : null)
                .roles(userRoles.stream().map(ur -> ur.getRole().getRoleName()).collect(Collectors.toList()))
                .isBanned(user.getIsBanned())
                .emailVerified(user.getEmailVerifiedAt() != null)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public TokenValidationResponse validateToken(String token) {
        String jwt = extractRawToken(token);

        try {
            Long userId = jwtService.extractUserId(jwt);
            List<String> roles = jwtService.extractRoles(jwt);

            if (userId == null) {
                throw new InvalidTokenException("Invalid token: missing user ID claim");
            }

            return TokenValidationResponse.builder().userId(userId).roles(roles).build();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid or expired token");
        }
    }

    private String extractRawToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidTokenException("Token is required");
        }

        if (token.startsWith("Bearer ")) {
            String bearerToken = token.substring(7);
            if (bearerToken.isBlank()) {
                throw new InvalidTokenException("Token is required");
            }
            return bearerToken;
        }

        return token;
    }

    @Override
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        String requestRefreshToken = request.getRefreshToken();
        String username = jwtService.extractUsername(requestRefreshToken);

        if (username != null) {
            UserEntity user = userRepository
                    .findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            // Check if token is valid
            if (jwtService.isTokenValid(requestRefreshToken, user)) {

                // Optional: Check if banned
                if (user.getIsBanned()) {
                    throw new AccountBannedException("Your account has been banned from the system");
                }

                // Generate new access token
                String token = jwtService.generateToken(user);

                // Load roles
                List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());

                return AuthResponse.builder()
                        .token(token)
                        .refreshToken(requestRefreshToken) // Return the same refresh token, or generate a new one
                        .userId(user.getUserId())
                        .email(user.getEmail())
                        .username(user.getUsername())
                        .departmentId(
                                user.getDepartment() != null
                                        ? user.getDepartment().getDepartmentId()
                                        : null)
                        .departmentName(
                                user.getDepartment() != null
                                        ? user.getDepartment().getDepartmentName()
                                        : null)
                        .roles(userRoles.stream()
                                .map(ur -> ur.getRole().getRoleName())
                                .collect(Collectors.toList()))
                        .isBanned(user.getIsBanned())
                        .emailVerified(user.getEmailVerifiedAt() != null)
                        .build();
            }
        }
        throw new InvalidTokenException("Invalid refresh token");
    }

    @Override
    public VerificationResponse verifyEmail(String rawToken) {
        UUID tokenId = parseToken(rawToken);
        VerificationToken token =
                verificationTokenRepository.findById(tokenId).orElseThrow(InvalidVerificationTokenException::unknown);

        UserEntity user =
                userRepository.findById(token.getUserId()).orElseThrow(InvalidVerificationTokenException::unknown);

        // A spent token means one of two quite different things, and only the account can
        // tell them apart.
        if (token.isUsed()) {
            // Redeemed already: a prefetching mail client, a double click, or the same mail
            // opened twice. The account is verified, which is what the caller was asking
            // for, so this is a 200 and not an error. No event -- nothing changed this time.
            if (user.getEmailVerifiedAt() != null) {
                return VerificationResponse.alreadyVerified(user.getEmail());
            }
            // Stamped used by a later resend rather than by a redemption. Reporting this as
            // "already verified" would tell the holder of a stale link they were finished
            // and then refuse them at sign-in with no way to connect the two.
            throw InvalidVerificationTokenException.superseded();
        }

        Instant now = Instant.now();
        if (token.isExpired(now)) {
            throw InvalidVerificationTokenException.expired();
        }

        token.markUsed(now);
        // Only the first token to arrive sets this. If the column is somehow already
        // populated -- an unused token surviving alongside a verified account -- keep the
        // original timestamp, since that is when the address was actually proved.
        if (user.getEmailVerifiedAt() == null) {
            user.setEmailVerifiedAt(now);
            outboxPublisher.publish(
                    UserVerifiedPayload.AGGREGATE_TYPE,
                    String.valueOf(user.getUserId()),
                    UserVerifiedPayload.TYPE,
                    new UserVerifiedPayload(user.getUserId(), user.getEmail(), user.getUsername(), now));
            log.info("Verified email for user {}", user.getUserId());
            return VerificationResponse.verified(user.getEmail());
        }

        return VerificationResponse.alreadyVerified(user.getEmail());
    }

    @Override
    public void resendVerification(ResendVerificationRequest request) {
        Optional<UserEntity> found = userRepository.findByEmail(request.getEmail());

        // An address with no account, and one that is already verified, are both answered
        // the same way the successful case is: 200, and no mail. There is nothing useful to
        // send, and a distinct response here would turn a public endpoint into a way of
        // asking which addresses are registered.
        if (found.isEmpty() || found.get().getEmailVerifiedAt() != null) {
            log.debug("Ignoring resend for an address that is unknown or already verified");
            return;
        }

        UserEntity user = found.get();
        Instant now = Instant.now();

        // The limit reads from the last token issued rather than from a counter in memory,
        // so it survives a restart and holds across instances -- the same reason the outbox
        // keeps its backoff state in the row.
        verificationTokenRepository
                .findFirstByUserIdOrderByCreatedAtDesc(user.getUserId())
                .filter(latest -> latest.getCreatedAt().isAfter(now.minus(RESEND_COOLDOWN)))
                .ifPresent(latest -> {
                    throw new ResendTooSoonException(
                            "A verification email was just sent. Wait a minute before asking for another.");
                });

        // Retire the outstanding links before minting the replacement, so that only the
        // newest mail works and an older one that leaked cannot still verify the account.
        verificationTokenRepository.invalidateOutstanding(user.getUserId(), now);
        publishRegistration(user);
        log.info("Reissued verification token for user {}", user.getUserId());
    }

    /**
     * Mints a verification token for a user and records the registration event carrying it.
     * Shared by registration and resend precisely so that the two produce the same event:
     * notification-service has one handler for it and should not have to care which of the
     * two asked.
     *
     * <p>Joins the caller's transaction, which is what {@link OutboxPublisher} requires.
     */
    private void publishRegistration(UserEntity user) {
        VerificationToken token =
                verificationTokenRepository.save(VerificationToken.issueFor(user.getUserId(), Instant.now()));

        outboxPublisher.publish(
                UserRegisteredPayload.AGGREGATE_TYPE,
                String.valueOf(user.getUserId()),
                UserRegisteredPayload.TYPE,
                new UserRegisteredPayload(
                        user.getUserId(),
                        user.getEmail(),
                        user.getUsername(),
                        token.getToken().toString(),
                        Instant.now()));
    }

    /**
     * A token that is not even a UUID never matched a row, so it is reported as unknown
     * rather than as a malformed request: to the person holding the link they are the same
     * thing, and the distinction only tells a prober how the value is stored.
     */
    private static UUID parseToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw InvalidVerificationTokenException.unknown();
        }
        try {
            return UUID.fromString(rawToken.trim());
        } catch (IllegalArgumentException ex) {
            throw InvalidVerificationTokenException.unknown();
        }
    }
}
