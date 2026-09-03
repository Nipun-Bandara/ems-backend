package com.ems.identity_service.security;

import com.ems.identity_service.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtService {

    static final String FAMILY_ID_CLAIM = "familyId";

    private final RsaKeyProvider rsaKeyProvider;

    @Value("${app.security.jwt.expiration}")
    private long jwtExpiration;

    @Value("${app.security.jwt.refresh-expiration}")
    private long refreshExpiration;

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Long extractUserId(String token) {
        return extractClaim(token, claims -> claims.get("userId", Long.class));
    }

    public Long extractDepartmentId(String token) {
        return extractClaim(token, claims -> claims.get("departmentId", Long.class));
    }

    /**
     * When the token was signed. Compared against
     * {@link UserEntity#getTokensValidFrom()} to reject tokens minted before a password reset.
     *
     * <p>{@code iat} is stored with second precision, so a token signed part-way through a
     * second reads as having been signed at the start of it. That rounding can only make a
     * token look older than it is, which errs towards refusing a token the reset should have
     * caught rather than honouring one it should not.
     */
    public Instant extractIssuedAt(String token) {
        Date issuedAt = extractClaim(token, Claims::getIssuedAt);
        return issuedAt == null ? null : issuedAt.toInstant();
    }

    /**
     * The {@code jti} naming this refresh token's row in {@link RefreshTokenStore}. Null for an
     * access token, which is not tracked and cannot be presented for a refresh.
     */
    public String extractJti(String token) {
        return extractClaim(token, Claims::getId);
    }

    /**
     * The chain of rotations this refresh token belongs to. Every token minted by refreshing
     * another inherits its family, so that finding one of them replayed is enough to retire the
     * whole line — see {@link RefreshTokenStore#revokeFamily}.
     */
    public String extractFamilyId(String token) {
        return extractClaim(token, claims -> claims.get(FAMILY_ID_CLAIM, String.class));
    }

    public List<String> extractRoles(String token) {
        Claims claims = extractAllClaims(token);
        @SuppressWarnings("unchecked")
        List<String> roles = claims.get("roles", List.class);
        return roles != null ? roles : List.of();
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(UserEntity userEntity) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userEntity.getUserId());
        claims.put("username", userEntity.getUsername());
        claims.put("email", userEntity.getEmail());
        if (userEntity.getDepartment() != null) {
            claims.put("departmentId", userEntity.getDepartment().getDepartmentId());
        }

        List<String> roles = userEntity.getUserRoles() != null
                ? userEntity.getUserRoles().stream()
                        .map(ur -> ur.getRole().getRoleName().name())
                        .collect(Collectors.toList())
                : List.of();
        claims.put("roles", roles);

        return buildToken(claims, userEntity.getUsername(), jwtExpiration, null);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails.getUsername(), jwtExpiration, null);
    }

    /**
     * Mints a refresh token naming a row that the caller is expected to write to
     * {@link RefreshTokenStore}. The {@code jti} is the only thing tying the token to that row,
     * so there is deliberately no overload that mints one without it: a refresh token the store
     * does not know about is indistinguishable from a replayed one and would be refused.
     *
     * @param jti unique per token, including across the rotations of one family
     * @param familyId shared by every token descended from the same sign-in
     */
    public String generateRefreshToken(UserEntity userEntity, String jti, String familyId) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(FAMILY_ID_CLAIM, familyId);
        return buildToken(claims, userEntity.getUsername(), refreshExpiration, jti);
    }

    /**
     * How long a refresh token is good for, as the TTL its {@link RefreshTokenStore} row is
     * given. The row and the signature have to expire together: a row outliving its token is
     * garbage, and a token outliving its row reads as a replay.
     */
    public Duration getRefreshTokenTtl() {
        return Duration.ofMillis(refreshExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration, String jti) {
        return Jwts.builder()
                // The kid lets a verifier pick the right key out of the JWK Set, and lets us roll
                // the key over without a flag day.
                .header()
                .keyId(rsaKeyProvider.getKeyId())
                .and()
                .claims(extraClaims)
                .id(jti)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(rsaKeyProvider.getPrivateKey(), Jwts.SIG.RS256)
                .compact();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(rsaKeyProvider.getPublicKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
