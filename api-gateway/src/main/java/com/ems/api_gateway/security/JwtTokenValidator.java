package com.ems.api_gateway.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Verifies RS256 access tokens against identity-service's JWK Set.
 *
 * <p>The key set is cached for {@link #CACHE_TIME_TO_LIVE}. A token whose {@code kid} is not in the
 * cached set triggers a refetch, so a key rollover takes effect without a restart; that refetch is
 * rate limited, so a flood of tokens carrying unknown key ids cannot turn into a flood of requests
 * to identity-service.
 */
@Component
public class JwtTokenValidator {

    private static final Duration CACHE_TIME_TO_LIVE = Duration.ofMinutes(5);
    /** How long a request waits for another thread's in-flight refresh before fetching itself. */
    private static final Duration CACHE_REFRESH_TIMEOUT = Duration.ofSeconds(15);
    /** Floor on the interval between refetches triggered by an unknown key id. */
    private static final Duration UNKNOWN_KID_REFRESH_INTERVAL = Duration.ofSeconds(30);

    private final ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

    public JwtTokenValidator(@Value("${app.security.jwt.jwks-uri}") String jwksUri) throws MalformedURLException {
        JWKSource<SecurityContext> jwkSource = JWKSourceBuilder.<SecurityContext>create(toUrl(jwksUri))
                .cache(CACHE_TIME_TO_LIVE.toMillis(), CACHE_REFRESH_TIMEOUT.toMillis())
                .rateLimited(UNKNOWN_KID_REFRESH_INTERVAL.toMillis())
                .build();

        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        // Pinning the algorithm is what stops a token from choosing its own — an RS256-only
        // selector will not verify an "alg": "none" or an HS256 token signed with the public key.
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwkSource));
        processor.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(null, Set.of("exp")));
        this.jwtProcessor = processor;
    }

    /**
     * @return the verified claims
     * @throws ParseException if the token is not a well formed JWT
     * @throws BadJOSEException if the signature, the expiry or a required claim does not hold up
     * @throws JOSEException if the key set could not be reached or the signature could not be
     *     checked
     */
    public JWTClaimsSet validate(String token) throws ParseException, BadJOSEException, JOSEException {
        return jwtProcessor.process(token, null);
    }

    private static URL toUrl(String jwksUri) throws MalformedURLException {
        try {
            return URI.create(jwksUri).toURL();
        } catch (IllegalArgumentException e) {
            throw new MalformedURLException("app.security.jwt.jwks-uri is not a valid URL: " + jwksUri);
        }
    }
}
