package com.ems.claim_service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {"ems.messaging.enabled=false", "ems.storage.enabled=true", "ems.claims.receipt-cleanup-ms=3600000"
        })
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ReceiptIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17");

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>(
                    "dappros/minio:RELEASE.2025-09-07T16-13-09Z@sha256:624bad2fe79a8613aa3c5c9b765ebcab365080b8445cf1b05c357cc7753725f0")
            .withEnv("MINIO_ROOT_USER", "ems_minio")
            .withEnv("MINIO_ROOT_PASSWORD", "ems_local_dev")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("ems.storage.endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        p.add("ems.storage.public-endpoint", () -> "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000));
        p.add("ems.storage.access-key", () -> "ems_minio");
        p.add("ems.storage.secret-key", () -> "ems_local_dev");
        p.add("ems.storage.path-style-access", () -> true);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    ClaimService claims;

    @Autowired
    EmployeeScopeStore scope;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ReceiptService receipts;

    @Autowired
    ReceiptCleanup cleanup;

    @Autowired
    S3Client s3;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.ems.common.storage.StorageService storage;

    private UUID draft(long owner) {
        var at = Instant.parse("2026-01-01T00:00:00Z");
        scope.reconcile(7201, new Models.EmployeeScope(true, 1L, null, at));
        scope.reconcile(owner, new Models.EmployeeScope(true, 1L, 7201L, at));
        scope.reconcile(7301, new Models.EmployeeScope(true, 2L, null, at));
        var c = claims.create(owner, new Models.Create("LKR"));
        claims.save(
                c.id(),
                owner,
                new Models.Save(
                        0L, List.of(new Models.Line("Travel", LocalDate.of(2026, 10, 1), "Train", "LKR", "2.00"))));
        return c.id();
    }

    private JsonNode intent(UUID claim, long owner, String type, long size) throws Exception {
        return json.readTree(mvc.perform(post("/api/claims/" + claim + "/receipts/upload-intents")
                        .header("X-User-Id", owner)
                        .contentType("application/json")
                        .content(json.writeValueAsString(
                                Map.of("filename", "receipt", "contentType", type, "size", size))))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private void putObject(JsonNode intent, byte[] bytes, String type) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(intent.path("uploadUrl").asText()))
                .header("Content-Type", type)
                .header("If-None-Match", "*")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        assertThat(HttpClient.newHttpClient()
                        .send(request, HttpResponse.BodyHandlers.ofString())
                        .statusCode())
                .isEqualTo(200);
    }

    private String base(UUID claim, JsonNode intent) {
        return "/api/claims/" + claim + "/receipts/" + intent.path("intentId").asText();
    }

    @Test
    void uploadSubmitAndAuthorizedDownloadAreIdempotentAndRetained() throws Exception {
        UUID c = draft(7101);
        mvc.perform(post("/api/claims/" + c + "/submit")
                        .header("X-User-Id", 7101)
                        .contentType("application/json")
                        .content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value(org.hamcrest.Matchers.containsString("at least one finalized receipt")));
        for (String type : List.of("application/pdf", "image/png")) {
            byte[] bytes = type.equals("application/pdf")
                    ? "%PDF-1.4\nreceipt\n%%EOF".getBytes()
                    : Base64.getDecoder()
                            .decode(
                                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aSmkAAAAASUVORK5CYII=");
            var i = intent(c, 7101, type, bytes.length);
            putObject(i, bytes, type);
            var overwrite = HttpRequest.newBuilder(
                            URI.create(i.path("uploadUrl").asText()))
                    .header("Content-Type", type)
                    .header("If-None-Match", "*")
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            assertThat(HttpClient.newHttpClient()
                            .send(overwrite, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(412);
            String path = base(c, i);
            mvc.perform(put(path + "/finalize").header("X-User-Id", 7301)).andExpect(status().isNotFound());
            mvc.perform(get(path + "/download").header("X-User-Id", 7201)).andExpect(status().isNotFound());
            for (int retry = 0; retry < 2; retry++)
                mvc.perform(put(path + "/finalize")
                                .header("X-User-Id", 7101)
                                .contentType("application/json")
                                .content("{\"objectKey\":\"arbitrary\"}"))
                        .andExpect(status().isOk());
        }
        assertThat(receipts.list(c, 7101)).hasSize(2);
        claims.submit(c, 7101, new Models.Transition(1L, null));
        jdbc.update("UPDATE claim_receipt SET expires_at=now()-interval '1 day' WHERE claim_id=?", c);
        cleanup.cleanup();
        for (var r : receipts.list(c, 7101)) {
            String path = "/api/claims/" + c + "/receipts/" + r.id();
            mvc.perform(get(path + "/download").header("X-User-Id", 7301)).andExpect(status().isForbidden());
            mvc.perform(delete(path).header("X-User-Id", 7101)).andExpect(status().isConflict());
            for (long actor : List.of(7101L, 7201L)) {
                var result = json.readTree(mvc.perform(get(path + "/download").header("X-User-Id", actor))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
                assertThat(HttpClient.newHttpClient()
                                .send(
                                        HttpRequest.newBuilder(URI.create(
                                                        result.path("url").asText()))
                                                .GET()
                                                .build(),
                                        HttpResponse.BodyHandlers.ofByteArray())
                                .body())
                        .hasSize((int) r.size());
            }
        }
    }

    @Test
    void expiredForeignAndOversizedObjectsCannotFinalizeAndCleanupCompensates() throws Exception {
        UUID c = draft(7102);
        var i = intent(c, 7102, "application/pdf", 10);
        String path = base(c, i);
        UUID id = UUID.fromString(i.path("intentId").asText());
        var key = jdbc.queryForObject("SELECT object_key FROM claim_receipt WHERE id=?", String.class, id);
        // Bypass the signed PUT's exact-length constraint using privileged test-only storage access.
        s3.putObject(
                b -> b.bucket(ReceiptService.BUCKET).key(key).contentType("application/pdf"),
                RequestBody.fromBytes(new byte[(int) ReceiptService.MAX_SIZE + 1]));
        mvc.perform(put(path + "/finalize").header("X-User-Id", 7102)).andExpect(status().isBadRequest());
        s3.putObject(
                b -> b.bucket(ReceiptService.BUCKET).key(key).contentType("text/plain"),
                RequestBody.fromBytes(new byte[10]));
        mvc.perform(put(path + "/finalize").header("X-User-Id", 7102)).andExpect(status().isBadRequest());
        UUID other = draft(7103);
        mvc.perform(put("/api/claims/" + other + "/receipts/" + id + "/finalize")
                        .header("X-User-Id", 7103))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/claims/" + c + "/receipts/upload-intents")
                        .header("X-User-Id", 7301)
                        .contentType("application/json")
                        .content("{\"filename\":\"x\",\"size\":1,\"contentType\":\"application/pdf\"}"))
                .andExpect(status().isNotFound());
        jdbc.update("UPDATE claim_receipt SET expires_at=now()-interval '1 day' WHERE id=?", id);
        mvc.perform(put(path + "/finalize").header("X-User-Id", 7102)).andExpect(status().isGone());
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_receipt WHERE id=?", Integer.class, id))
                .isZero();
        assertThatThrownBy(
                        () -> s3.headObject(b -> b.bucket(ReceiptService.BUCKET).key(key)))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
    }

    @Test
    void draftDeleteAndCancelledIntentsAreCompensatedAfterExpiry() throws Exception {
        UUID c = draft(7104);
        var i = intent(c, 7104, "application/pdf", 2);
        putObject(i, new byte[2], "application/pdf");
        String path = base(c, i);
        mvc.perform(put(path + "/finalize").header("X-User-Id", 7104)).andExpect(status().isOk());
        mvc.perform(delete(path).header("X-User-Id", 7104)).andExpect(status().isNoContent());
        assertThat(receipts.list(c, 7104)).isEmpty();
        var unfinished = intent(c, 7104, "image/png", 2);
        putObject(unfinished, new byte[2], "image/png");
        claims.delete(c, 7104, 1);
        jdbc.update("UPDATE claim_receipt SET expires_at=now()-interval '1 day' WHERE owner_id=7104");
        String key = jdbc.queryForObject(
                "SELECT object_key FROM claim_receipt WHERE id=?",
                String.class,
                UUID.fromString(unfinished.path("intentId").asText()));
        org.mockito.Mockito.doThrow(new RuntimeException("simulated storage outage"))
                .doCallRealMethod()
                .when(storage)
                .delete(ReceiptService.BUCKET, key);
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_receipt WHERE owner_id=7104", Integer.class))
                .isEqualTo(1);
        cleanup.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM claim_receipt WHERE owner_id=7104", Integer.class))
                .isZero();
    }
}
