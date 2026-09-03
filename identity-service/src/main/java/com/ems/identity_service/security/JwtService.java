package com.ems.identity_service.security;

import com.ems.identity_service.entity.UserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
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

        return buildToken(claims, userEntity.getUsername(), jwtExpiration);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails.getUsername(), jwtExpiration);
    }

    public String generateRefreshToken(UserDetails userDetails) {
        return buildToken(new HashMap<>(), userDetails.getUsername(), refreshExpiration);
    }

    public String generateRefreshToken(UserEntity userEntity) {
        return buildToken(new HashMap<>(), userEntity.getUsername(), refreshExpiration);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long expiration) {
        return Jwts.builder()
                // The kid lets a verifier pick the right key out of the JWK Set, and lets us roll
                // the key over without a flag day.
                .header()
                .keyId(rsaKeyProvider.getKeyId())
                .and()
                .claims(extraClaims)
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
