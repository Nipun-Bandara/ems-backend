package com.ems.recruitment_service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.*;

/** Exercises the packaged gateway, its actual route profiles and real RS256 verification. */
final class RecruitmentGatewayProbe {
    static void verify(int recruitmentPort) throws Exception {
        Path gatewayJar =
                Path.of("../api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(gatewayJar)
                .as("Build api-gateway alongside recruitment-service for HTTP verification")
                .exists();
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var publicKey = (RSAPublicKey) keys.getPublic();
        String jwks =
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"recruitment-test\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""
                        + unsigned(publicKey.getModulus()) + "\",\"e\":\"" + unsigned(publicKey.getPublicExponent())
                        + "\"}]}";
        HttpServer jwkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwkServer.createContext("/jwks", exchange -> {
            byte[] bytes = jwks.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) {
                body.write(bytes);
            }
        });
        jwkServer.start();
        try {
            String input =
                    encode("{\"alg\":\"RS256\",\"kid\":\"recruitment-test\"}".getBytes(StandardCharsets.UTF_8)) + "."
                            + encode(("{\"userId\":42,\"roles\":[\"EMPLOYEE\"],\"exp\":"
                                            + (System.currentTimeMillis() / 1000 + 600) + "}")
                                    .getBytes(StandardCharsets.UTF_8));
            var signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(keys.getPrivate());
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            String token = input + "." + encode(signer.sign());
            var client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            for (String profile : List.of("development", "staging", "production")) {
                int port;
                try (var socket = new java.net.ServerSocket(0)) {
                    port = socket.getLocalPort();
                }
                Path log = Path.of("target/gateway-" + profile + ".log");
                Process process = new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java")
                                        .toString(),
                                "-jar",
                                gatewayJar.toString(),
                                "--server.port=" + port,
                                "--spring.profiles.active=" + profile,
                                "--spring.config.import=",
                                "--ALLOWED_ORIGINS=http://localhost:3000",
                                "--IDENTITY_SERVICE_URL=http://127.0.0.1:1",
                                "--ORG_SERVICE_URL=http://127.0.0.1:1",
                                "--EMPLOYEE_SERVICE_URL=http://127.0.0.1:1",
                                "--RECRUITMENT_SERVICE_URL=http://127.0.0.1:" + recruitmentPort,
                                "--JWKS_URI=http://127.0.0.1:"
                                        + jwkServer.getAddress().getPort() + "/jwks",
                                "--spring.data.redis.port=1",
                                "--spring.data.redis.connect-timeout=100ms",
                                "--spring.data.redis.timeout=100ms")
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
                try {
                    URI ping = URI.create("http://127.0.0.1:" + port + "/api/recruitment/ping");
                    await().atMost(Duration.ofSeconds(60))
                            .pollInterval(Duration.ofMillis(500))
                            .ignoreExceptions()
                            .untilAsserted(() -> {
                                assertThat(process.isAlive())
                                        .as("Gateway startup; see %s", log)
                                        .isTrue();
                                assertThat(send(client, ping, null).statusCode())
                                        .isEqualTo(401);
                            });
                    var response = send(client, ping, token);
                    assertThat(response.statusCode())
                            .as("Authenticated ping in %s: %s", profile, response.body())
                            .isEqualTo(200);
                    assertThat(response.body()).contains("recruitment-service", "\"userId\":\"42\"");
                    assertThat(response.headers().firstValue("X-Correlation-Id"))
                            .contains("recruitment-gateway-probe");
                    assertThat(send(client, ping, "invalid").statusCode()).isEqualTo(401);
                    URI admin = URI.create("http://127.0.0.1:" + port + "/api/recruitment/admin/ping");
                    assertThat(send(client, admin, token).statusCode())
                            .as("Employee JWT cannot spoof SYSTEM_ADMIN")
                            .isEqualTo(403);
                    System.out.println("Gateway " + profile
                            + ": authenticated 200, anonymous/spoofed 401, invalid token 401, correlation preserved");
                } finally {
                    process.destroy();
                    if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        process.waitFor();
                    }
                }
            }
        } finally {
            jwkServer.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI uri, String token) throws Exception {
        var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("X-User-Id", "999")
                .header("X-User-Roles", "SYSTEM_ADMIN")
                .header("X-Correlation-Id", "recruitment-gateway-probe");
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String unsigned(java.math.BigInteger value) {
        byte[] bytes = value.toByteArray();
        return encode(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
