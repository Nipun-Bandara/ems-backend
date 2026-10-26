package com.ems.common.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Opt-in smoke test against docker-compose MinIO. All payloads stay in memory. */
@EnabledIfEnvironmentVariable(named = "STORAGE_INTEGRATION_TEST", matches = "true")
class StorageIntegrationTest {
    @Test
    void directPutDownloadHeadDeleteAndCreateOnlySignature() throws Exception {
        var config = new StorageAutoConfiguration();
        var properties = new StorageProperties(
                "http://localhost:9000",
                "http://localhost:9000",
                "us-east-1",
                "ems_minio",
                "ems_local_dev",
                true,
                List.of("http://localhost:3000"));
        var credentials = config.storageCredentials(properties);
        try (var client = config.storageClient(properties, credentials);
                var signer = config.storagePresigner(properties, credentials)) {
            new BucketBootstrap(client, properties).afterPropertiesSet();
            var storage = new StorageService(client, signer);
            var http = HttpClient.newHttpClient();
            byte[] bytes = new byte[] {(byte) 137, 80, 78, 71};
            String key = "storage-smoke/" + UUID.randomUUID();
            try {
                String url =
                        storage.presignedUpload("ems-avatars", key, "image/png", bytes.length, Duration.ofMinutes(1));
                assertTrue(url.contains("content-length"), "Exact upload length must be signed");
                assertTrue(url.contains("if-none-match"), "Create-only header must be signed");
                var request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "image/png")
                        .header("If-None-Match", "*")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                        .build();
                assertEquals(
                        200,
                        http.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                assertEquals(
                        412,
                        http.send(request, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                var changedLength = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "image/png")
                        .header("If-None-Match", "*")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(new byte[bytes.length + 1]))
                        .build();
                assertEquals(
                        403,
                        http.send(changedLength, HttpResponse.BodyHandlers.discarding())
                                .statusCode());
                var preflight = HttpRequest.newBuilder(URI.create("http://localhost:9000/ems-avatars/" + key))
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "PUT")
                        .header("Access-Control-Request-Headers", "content-type,if-none-match")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .build();
                var cors = http.send(preflight, HttpResponse.BodyHandlers.discarding());
                assertTrue(cors.statusCode() < 300);
                assertTrue(
                        cors.headers().firstValue("access-control-allow-origin").isPresent());
                var head = storage.head("ems-avatars", key);
                assertEquals(bytes.length, head.contentLength());
                assertEquals("image/png", head.contentType());
                var get = HttpRequest.newBuilder(
                                URI.create(storage.presignedDownload("ems-avatars", key, Duration.ofMinutes(1))))
                        .GET()
                        .build();
                var response = http.send(get, HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode());
                assertArrayEquals(bytes, response.body());
            } finally {
                storage.delete("ems-avatars", key);
            }
        }
    }
}
