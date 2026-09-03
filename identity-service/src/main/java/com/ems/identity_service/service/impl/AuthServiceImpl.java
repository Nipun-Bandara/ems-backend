package com.ems.identity_service.service.impl;

import com.ems.common.outbox.OutboxPublisher;
import com.ems.identity_service.dto.request.ForgotPasswordRequest;
import com.ems.identity_service.dto.request.LoginRequest;
import com.ems.identity_service.dto.request.RefreshTokenRequest;
import com.ems.identity_service.dto.request.RegisterRequest;
import com.ems.identity_service.dto.request.ResendVerificationRequest;
import com.ems.identity_service.dto.request.ResetPasswordRequest;
import com.ems.identity_service.dto.response.AuthResponse;
import com.ems.identity_service.dto.response.ForgotPasswordResponse;
import com.ems.identity_service.dto.response.PasswordResetResponse;
import com.ems.identity_service.dto.response.TokenValidationResponse;
import com.ems.identity_service.dto.response.VerificationResponse;
import com.ems.identity_service.entity.PasswordResetToken;
import com.ems.identity_service.entity.RoleEntity;
import com.ems.identity_service.entity.UserEntity;
import com.ems.identity_service.entity.UserRoles;
import com.ems.identity_service.entity.VerificationToken;
import com.ems.identity_service.event.PasswordChangedPayload;
import com.ems.identity_service.event.PasswordResetRequestedPayload;
import com.ems.identity_service.event.UserRegisteredPayload;
import com.ems.identity_service.event.UserVerifiedPayload;
import com.ems.identity_service.exception.AccountBannedException;
import com.ems.identity_service.exception.EmailNotVerifiedException;
import com.ems.identity_service.exception.InvalidPasswordResetTokenException;
import com.ems.identity_service.exception.InvalidTokenException;
import com.ems.identity_service.exception.InvalidVerificationTokenException;
import com.ems.identity_service.exception.ResendTooSoonException;
import com.ems.identity_service.exception.TokenReuseException;
import com.ems.identity_service.repository.PasswordResetTokenRepository;
import com.ems.identity_service.repository.RoleRepository;
import com.ems.identity_service.repository.UserRepository;
import com.ems.identity_service.repository.UserRolesRepository;
import com.ems.identity_service.repository.VerificationTokenRepository;
import com.ems.identity_service.security.AuthenticatedUser;
import com.ems.identity_service.security.JwtService;
import com.ems.identity_service.security.RefreshTokenStore;
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

    /**
     * The window the password reset limit is measured over, and how many links an address may
     * be sent within it. A cooldown like {@link #RESEND_COOLDOWN} would be the wrong shape
     * here: someone who does not see the first mail should be able to try again shortly, but
     * an address must not be made to receive reset links all day.
     */
    private static final Duration RESET_WINDOW = Duration.ofHours(1);

    private static final long MAX_RESETS_PER_WINDOW = 3;

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRolesRepository userRolesRepository;
    private final VerificationTokenRepository verificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
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

        // A new sign-in starts its own family. Nothing links it to the sessions this account
        // already has, so revoking one for replay leaves the others alone.
        String refreshToken = issueRefreshToken(user, UUID.randomUUID().toString());
        String token = jwtService.generateToken(user);

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
        String presented = request.getRefreshToken();

        // Parsing verifies the signature and the expiry in one step, and an expired token
        // throws here rather than reaching the store. That ordering matters: its row has
        // expired too, so checking the store first would report an ordinary timeout as a
        // replay and revoke the family over it.
        String username;
        String jti;
        String familyId;
        try {
            username = jwtService.extractUsername(presented);
            jti = jwtService.extractJti(presented);
            familyId = jwtService.extractFamilyId(presented);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        // No jti or no family means this is not a refresh token -- an access token, or one
        // minted before rotation existed. Neither names a row, so neither can be spent, and
        // treating them as replays would revoke a family over presenting the wrong token.
        if (username == null || jti == null || familyId == null) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        UserEntity user = userRepository
                .findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        // Before the ban and revocation checks, because this one is the reason to act on a
        // token rather than a reason to refuse it. A stolen token presented for a banned
        // account should still take the family down with it.
        if (!refreshTokenStore.consume(user.getUserId(), jti)) {
            log.warn(
                    "Refresh token {} for user {} was presented after being spent; revoking family {}",
                    jti,
                    user.getUserId(),
                    familyId);
            refreshTokenStore.revokeFamily(user.getUserId(), familyId);
            throw new TokenReuseException(
                    "This session has been ended because a refresh token was used twice. Sign in again.");
        }

        if (user.getIsBanned()) {
            throw new AccountBannedException("Your account has been banned from the system");
        }

        // A signature that still verifies is not enough on its own: a password reset
        // revokes the sessions that predate it, and this is where that takes effect for
        // anything the reset's own sweep of the store somehow missed. Refused as an invalid
        // refresh token rather than with a distinct error -- there is nothing the holder can
        // do about it except sign in again, which is what a rejected refresh already tells
        // them to do.
        if (isRevoked(presented, user)) {
            log.info("Refusing a refresh token for user {} issued before its credentials changed", user.getUserId());
            throw new InvalidTokenException("Invalid refresh token");
        }

        // Load roles
        List<UserRoles> userRoles = userRolesRepository.findByUser_UserId(user.getUserId());
        user.setUserRoles(userRoles);

        // Same family, new jti: the replacement is as revocable as what it replaced, and
        // finding either of them replayed later retires both.
        String refreshToken = issueRefreshToken(user, familyId);
        String token = jwtService.generateToken(user);

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
                .emailVerified(user.getEmailVerifiedAt() != null)
                .build();
    }

    @Override
    public void logout(RefreshTokenRequest request) {
        Long userId = AuthenticatedUser.requireUserId(
                SecurityContextHolder.getContext().getAuthentication());

        String jti;
        try {
            jti = jwtService.extractJti(request.getRefreshToken());
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid refresh token");
        }
        if (jti == null) {
            throw new InvalidTokenException("Invalid refresh token");
        }

        // Under the caller's own id, never the one in the token: the access token is what
        // proves who is asking, so a refresh token belonging to somebody else names a key
        // that does not exist and deletes nothing.
        //
        // The result is ignored on purpose. A token that was already spent leaves the caller
        // exactly where they wanted to be, so signing out twice is not an error.
        refreshTokenStore.consume(userId, jti);
        log.info("Signed out one session for user {}", userId);
    }

    @Override
    public void logoutAll() {
        Long userId = AuthenticatedUser.requireUserId(
                SecurityContextHolder.getContext().getAuthentication());

        refreshTokenStore.revokeAll(userId);
    }

    /**
     * Mints a refresh token in the given family and records it, returning the token.
     *
     * <p>The row is written before the token is handed out, so there is no window in which a
     * caller holds a token the store has never heard of — which is exactly what a replay looks
     * like. The reverse ordering would turn a crash between the two into a session that is
     * refused, and its family revoked, the first time it is refreshed.
     */
    private String issueRefreshToken(UserEntity user, String familyId) {
        String jti = UUID.randomUUID().toString();
        refreshTokenStore.store(user.getUserId(), jti, familyId, Instant.now(), jwtService.getRefreshTokenTtl());
        return jwtService.generateRefreshToken(user, jti, familyId);
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

    @Override
    public ForgotPasswordResponse forgotPassword(ForgotPasswordRequest request) {
        Optional<UserEntity> found = userRepository.findByEmail(request.getEmail());

        // Every path out of this method returns the same response. An address with no account
        // gets it, an address over its limit gets it, and a real request gets it -- the reply
        // says only that a link would be sent if there were somewhere to send it.
        if (found.isEmpty()) {
            log.debug("Ignoring a password reset request for an address with no account");
            return ForgotPasswordResponse.generic();
        }

        UserEntity user = found.get();
        Instant now = Instant.now();

        // Counted from the token rows rather than from a counter in memory, so the limit
        // survives a restart and holds across instances -- the same reason resendVerification
        // reads its cooldown from the last token issued.
        long issuedInLastHour =
                passwordResetTokenRepository.countByUserIdAndCreatedAtAfter(user.getUserId(), now.minus(RESET_WINDOW));
        if (issuedInLastHour >= MAX_RESETS_PER_WINDOW) {
            // Silently, and not as the 429 resendVerification throws. A 429 here would only
            // ever be reachable for an address that has an account -- an unknown one can never
            // accumulate tokens -- so the status alone would answer the question the generic
            // response exists to refuse. The limit still does its job: no mail goes out.
            log.info(
                    "Suppressing password reset for user {}: {} already issued this hour",
                    user.getUserId(),
                    issuedInLastHour);
            return ForgotPasswordResponse.generic();
        }

        // Retire the outstanding links before minting the replacement, so only the newest mail
        // works and an older one that leaked cannot still take the account.
        passwordResetTokenRepository.invalidateOutstanding(user.getUserId(), now);

        PasswordResetToken token =
                passwordResetTokenRepository.save(PasswordResetToken.issueFor(user.getUserId(), now));

        // Row and event in one transaction, as everywhere else: the token is committed with
        // the event that carries it or neither exists, so a link can never be mailed for a
        // token the database does not hold.
        outboxPublisher.publish(
                PasswordResetRequestedPayload.AGGREGATE_TYPE,
                String.valueOf(user.getUserId()),
                PasswordResetRequestedPayload.TYPE,
                new PasswordResetRequestedPayload(
                        user.getUserId(),
                        user.getEmail(),
                        user.getUsername(),
                        token.getToken().toString(),
                        now));

        log.info("Issued password reset token for user {}", user.getUserId());
        return ForgotPasswordResponse.generic();
    }

    @Override
    public PasswordResetResponse resetPassword(ResetPasswordRequest request) {
        UUID tokenId = parseResetToken(request.getToken());
        PasswordResetToken token =
                passwordResetTokenRepository.findById(tokenId).orElseThrow(InvalidPasswordResetTokenException::unknown);

        // Spent is an error here, unlike email verification. Verifying twice still leaves the
        // address verified, so it can be answered as success; resetting twice does not apply
        // the second password, and reporting success would lock the caller out of an account
        // they believe they just changed.
        if (token.isUsed()) {
            throw InvalidPasswordResetTokenException.used();
        }

        Instant now = Instant.now();
        if (token.isExpired(now)) {
            throw InvalidPasswordResetTokenException.expired();
        }

        UserEntity user =
                userRepository.findById(token.getUserId()).orElseThrow(InvalidPasswordResetTokenException::unknown);

        token.markUsed(now);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));

        // Everything issued before this instant stops being refreshable. Whoever asked for the
        // reset is presumed to have lost control of the account, so the sessions that predate
        // it are exactly the ones that should not survive -- including the attacker's, if the
        // reason for the reset was that someone else had signed in.
        user.setTokensValidFrom(now);

        // The watermark alone would do it, but deleting the rows is what actually ends the
        // sessions rather than only refusing to extend them, and it is what keeps the store
        // from holding tokens nothing will ever honour again.
        //
        // Outside the transaction's control: if the commit below fails, the tokens are gone
        // and the password is unchanged. That errs towards signing a user out of sessions
        // they could have kept, which is the harmless direction -- the other ordering would
        // leave a reset password with the old sessions still live.
        refreshTokenStore.revokeAll(user.getUserId());

        outboxPublisher.publish(
                PasswordChangedPayload.AGGREGATE_TYPE,
                String.valueOf(user.getUserId()),
                PasswordChangedPayload.TYPE,
                new PasswordChangedPayload(user.getUserId(), user.getEmail(), user.getUsername(), now));

        log.info("Reset password for user {} and revoked refresh tokens issued before {}", user.getUserId(), now);
        return new PasswordResetResponse(user.getEmail());
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

    /**
     * Whether a token was signed before the account's credentials last changed.
     *
     * <p>A token with no {@code iat} is treated as revoked. Everything this service signs has
     * one, so a token without it did not come from here in the shape we issue — and when the
     * question is whether a session survived a password reset, the answer for something
     * unrecognised is no.
     */
    private boolean isRevoked(String token, UserEntity user) {
        Instant validFrom = user.getTokensValidFrom();
        if (validFrom == null) {
            return false;
        }
        Instant issuedAt = jwtService.extractIssuedAt(token);
        return issuedAt == null || issuedAt.isBefore(validFrom);
    }

    /** As {@link #parseToken}, for reset links: a value that is not a UUID never named a row. */
    private static UUID parseResetToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw InvalidPasswordResetTokenException.unknown();
        }
        try {
            return UUID.fromString(rawToken.trim());
        } catch (IllegalArgumentException ex) {
            throw InvalidPasswordResetTokenException.unknown();
        }
    }
}
