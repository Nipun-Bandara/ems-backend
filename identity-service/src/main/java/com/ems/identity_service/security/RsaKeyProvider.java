package com.ems.identity_service.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.RsaPublicJwk;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The RSA keypair this service signs access tokens with, and publishes the public half of at
 * {@code /.well-known/jwks.json}.
 *
 * <p>Both keys are read from PEM files named by {@code JWT_PRIVATE_KEY_PATH} and
 * {@code JWT_PUBLIC_KEY_PATH}. When they are not configured — or point at files that do not exist
 * yet — the development profile falls back to a keypair generated at startup so a fresh checkout
 * boots without a key ceremony. Every other profile fails fast instead: an ephemeral key would
 * invalidate every issued token on restart and differ across replicas.
 *
 * <p>The private key must be PKCS#8 and the public key X.509 {@code SubjectPublicKeyInfo}, which is
 * what OpenSSL writes by default:
 *
 * <pre>
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
 *   openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
 * </pre>
 */
@Component
public class RsaKeyProvider {

    private static final Logger log = LoggerFactory.getLogger(RsaKeyProvider.class);

    private static final String DEVELOPMENT_PROFILE = "development";
    private static final int GENERATED_KEY_SIZE = 2048;
    private static final Pattern PEM_ENVELOPE = Pattern.compile("-----(?:BEGIN|END)[^-]*-----");

    private final RSAPrivateKey privateKey;
    private final RSAPublicKey publicKey;
    private final RsaPublicJwk publicJwk;

    public RsaKeyProvider(
            @Value("${app.security.jwt.private-key-path:}") String privateKeyPath,
            @Value("${app.security.jwt.public-key-path:}") String publicKeyPath,
            Environment environment) {
        KeyPair keyPair = resolveKeyPair(privateKeyPath, publicKeyPath, environment);
        this.privateKey = (RSAPrivateKey) keyPair.getPrivate();
        this.publicKey = (RSAPublicKey) keyPair.getPublic();
        // The kid is the RFC 7638 thumbprint, so it is derived from the key itself and stays
        // stable across restarts for as long as the key does.
        this.publicJwk = Jwks.builder()
                .key(this.publicKey)
                .publicKeyUse("sig")
                .algorithm(Jwts.SIG.RS256.getId())
                .idFromThumbprint()
                .build();
    }

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    /** The {@code kid} written into the header of every token this service signs. */
    public String getKeyId() {
        return publicJwk.getId();
    }

    /** The public half, ready to be served in a JWK Set. */
    public RsaPublicJwk getPublicJwk() {
        return publicJwk;
    }

    private static KeyPair resolveKeyPair(String privateKeyPath, String publicKeyPath, Environment environment) {
        boolean configured = StringUtils.hasText(privateKeyPath) && StringUtils.hasText(publicKeyPath);

        if (configured) {
            Path privatePem = Path.of(privateKeyPath);
            Path publicPem = Path.of(publicKeyPath);
            if (Files.exists(privatePem) && Files.exists(publicPem)) {
                return loadKeyPair(privatePem, publicPem);
            }
            requireDevelopment(
                    environment,
                    "app.security.jwt.private-key-path and app.security.jwt.public-key-path point at " + privatePem
                            + " and " + publicPem + ", but the files do not exist");
            log.warn(
                    "No RSA keypair at {} and {}. Generating an ephemeral {}-bit keypair for the "
                            + "development profile — every restart invalidates the tokens issued before it.",
                    privatePem,
                    publicPem,
                    GENERATED_KEY_SIZE);
        } else {
            requireDevelopment(environment, "JWT_PRIVATE_KEY_PATH and JWT_PUBLIC_KEY_PATH are not set");
            log.warn(
                    "JWT_PRIVATE_KEY_PATH / JWT_PUBLIC_KEY_PATH are not set. Generating an ephemeral "
                            + "{}-bit keypair for the development profile — every restart invalidates the "
                            + "tokens issued before it.",
                    GENERATED_KEY_SIZE);
        }

        return generateKeyPair();
    }

    private static void requireDevelopment(Environment environment, String problem) {
        if (!environment.matchesProfiles(DEVELOPMENT_PROFILE)) {
            throw new IllegalStateException(problem
                    + ". A persistent RSA keypair is required outside the development profile, because a "
                    + "generated one would differ between restarts and between replicas.");
        }
    }

    private static KeyPair loadKeyPair(Path privateKeyPath, Path publicKeyPath) {
        try {
            KeyFactory rsa = KeyFactory.getInstance("RSA");
            KeyPair keyPair = new KeyPair(
                    rsa.generatePublic(new X509EncodedKeySpec(decodePem(publicKeyPath, "PUBLIC KEY"))),
                    rsa.generatePrivate(new PKCS8EncodedKeySpec(decodePem(privateKeyPath, "PRIVATE KEY"))));
            log.info("Loaded the RSA signing keypair from {} and {}", privateKeyPath, publicKeyPath);
            return keyPair;
        } catch (IOException | GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                    "Could not load the RSA keypair from " + privateKeyPath + " and " + publicKeyPath, e);
        }
    }

    private static byte[] decodePem(Path path, String label) throws IOException {
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        if (!pem.contains("-----BEGIN " + label + "-----")) {
            throw new IllegalArgumentException(path + " does not hold a PEM-encoded '" + label
                    + "'. A PKCS#1 key ('BEGIN RSA PRIVATE KEY') can be converted with: "
                    + "openssl pkey -in " + path + " -out " + path);
        }
        return Base64.getDecoder()
                .decode(PEM_ENVELOPE.matcher(pem).replaceAll("").replaceAll("\\s", ""));
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(GENERATED_KEY_SIZE);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not generate an RSA keypair", e);
        }
    }
}
