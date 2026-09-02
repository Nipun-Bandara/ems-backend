package com.ems.identity_service.controller;

import com.ems.identity_service.security.RsaKeyProvider;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Publishes the public half of the token signing key so the gateway — and any other service that
 * needs to verify a token — can do so without sharing a secret.
 */
@RestController
@RequiredArgsConstructor
public class JwksController {

    private final RsaKeyProvider rsaKeyProvider;

    @GetMapping("/.well-known/jwks.json")
    public ResponseEntity<JwkSet> jwks() {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Jwks.set().add(rsaKeyProvider.getPublicJwk()).build());
    }
}
