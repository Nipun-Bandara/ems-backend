package com.ems.claim_service;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.ems.common.event.EventEnvelope;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"ems.messaging.enabled=false", "ems.storage.enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ClaimIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17").withDatabaseName("claim_test");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @Autowired
    EmployeeScopeStore scope;

    @Autowired
    ProjectionHandler projections;

    private final Instant at = Instant.parse("2026-01-01T00:00:00Z");

    private void seed(long employee, long manager) {
        scope.reconcile(manager, new Models.EmployeeScope(true, 1L, null, at));
        scope.reconcile(employee, new Models.EmployeeScope(true, 1L, manager, at));
    }

    private JsonNode create(long actor) throws Exception {
        return json.readTree(mvc.perform(post("/api/claims")
                        .header("X-User-Id", actor)
                        .contentType("application/json")
                        .content("{\"currency\":\"LKR\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    }

    private String lines(String amount, String currency) {
        return "{\"version\":0,\"lines\":[{\"category\":\"Travel\",\"expenseDate\":\"2026-10-01\",\"description\":\"Train\",\"currency\":\""
                + currency + "\",\"amount\":\"" + amount
                + "\"},{\"category\":\"Meals\",\"expenseDate\":\"2026-10-01\",\"description\":\"Lunch\",\"currency\":\"LKR\",\"amount\":\"0.20\"}]}";
    }

    private String saved(long actor, long manager) throws Exception {
        seed(actor, manager);
        String id = create(actor).path("id").asText();
        mvc.perform(put("/api/claims/" + id)
                        .header("X-User-Id", actor)
                        .contentType("application/json")
                        .content(lines("0.10", "LKR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value("0.30"))
                .andExpect(jsonPath("$.lines.length()").value(2));
        jdbc.update(
                "INSERT INTO claim_receipt(id,claim_id,owner_id,object_key,filename,content_type,size,expires_at,finalized) VALUES (?,?,?,?,?,'application/pdf',1,now(),true)",
                UUID.randomUUID(),
                UUID.fromString(id),
                actor,
                "test/" + UUID.randomUUID(),
                "fixture.pdf");
        return id;
    }

    private int transition(String id, long actor, String action, long version, String reason) throws Exception {
        return mvc.perform(post("/api/claims/" + id + "/" + action)
                        .header("X-User-Id", actor)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("version", version, "reason", reason))))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    @Test
    void exactDecimalPersistenceReviewAndRetrySafety() throws Exception {
        String id = saved(101, 201);
        assertThat(jdbc.queryForObject("SELECT total::text FROM claim WHERE id=?", String.class, UUID.fromString(id)))
                .isEqualTo("0.30");
        assertThat(transition(id, 101, "submit", 1, "")).isEqualTo(200);
        assertThat(transition(id, 101, "submit", 1, "")).isEqualTo(200);
        mvc.perform(put("/api/claims/" + id)
                        .header("X-User-Id", 101)
                        .contentType("application/json")
                        .content(lines("5", "LKR")))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/claims/" + id + "?version=2").header("X-User-Id", 101))
                .andExpect(status().isConflict());
        assertThat(transition(id, 101, "approve", 2, "")).isEqualTo(403);
        scope.reconcile(301, new Models.EmployeeScope(true, 2L, null, at));
        assertThat(transition(id, 301, "approve", 2, "")).isEqualTo(403);
        mvc.perform(get("/api/claims/" + id).header("X-User-Id", 301)).andExpect(status().isNotFound());
        mvc.perform(get("/api/claims/reviewer/" + id).header("X-User-Id", 301)).andExpect(status().isForbidden());
        mvc.perform(get("/api/claims/reviewer/inbox").header("X-User-Id", 201))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isNotEmpty());
        assertThat(transition(id, 201, "approve", 2, "")).isEqualTo(200);
        assertThat(transition(id, 201, "approve", 2, "")).isEqualTo(409);
        assertThat(transition(id, 101, "submit", 1, "")).isEqualTo(200);
        mvc.perform(get("/api/claims/" + id).header("X-User-Id", 101))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.history.length()").value(4));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE aggregate_id=?", Long.class, id))
                .isEqualTo(2);
    }

    @Test
    void draftDeletionAndValidation() throws Exception {
        seed(102, 202);
        String id = create(102).path("id").asText();
        for (String amount : List.of("0", "-1", "0.001", "100000000000000000.00"))
            mvc.perform(put("/api/claims/" + id)
                            .header("X-User-Id", 102)
                            .contentType("application/json")
                            .content(lines(amount, "LKR")))
                    .andExpect(status().isBadRequest());
        mvc.perform(put("/api/claims/" + id)
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content(lines("1", "USD")))
                .andExpect(status().isBadRequest());
        assertThat(transition(id, 102, "submit", 0, "")).isEqualTo(400);
        mvc.perform(get("/api/claims/reviewer/" + id).header("X-User-Id", 202)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/claims/" + id + "?version=1").header("X-User-Id", 102))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/claims/" + id + "?version=0").header("X-User-Id", 102))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/claims/me")).andExpect(status().isUnauthorized());
        mvc.perform(put("/api/claims/admin/employees/999")
                        .header("X-User-Id", 102)
                        .contentType("application/json")
                        .content("{\"active\":true,\"asOf\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectionReasonAndPendingWithdrawal() throws Exception {
        String id = saved(103, 203);
        assertThat(transition(id, 103, "submit", 1, "")).isEqualTo(200);
        assertThat(transition(id, 203, "reject", 2, " ")).isEqualTo(400);
        assertThat(transition(id, 203, "reject", 2, "Receipt missing")).isEqualTo(200);
        assertThat(transition(id, 103, "withdraw", 3, "")).isEqualTo(409);
        mvc.perform(get("/api/claims/" + id).header("X-User-Id", 103))
                .andExpect(jsonPath("$.history[3].reason").value("Receipt missing"));
        String second = saved(104, 204);
        assertThat(transition(second, 104, "submit", 1, "")).isEqualTo(200);
        assertThat(transition(second, 104, "withdraw", 2, "")).isEqualTo(200);
        assertThat(transition(second, 104, "withdraw", 2, "")).isEqualTo(200);
        assertThat(transition(second, 204, "approve", 2, "")).isEqualTo(409);
    }

    @Test
    void concurrentDecisionsAndWithdrawalHaveOneWinner() throws Exception {
        for (boolean withdraw : List.of(false, true)) {
            long actor = withdraw ? 106 : 105, manager = withdraw ? 206 : 205;
            String id = saved(actor, manager);
            transition(id, actor, "submit", 1, "");
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                var first = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transition(id, manager, "approve", 2, "");
                });
                var second = pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return transition(id, withdraw ? actor : manager, withdraw ? "withdraw" : "reject", 2, "Check");
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder(200, 409);
            }
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM claim_history WHERE claim_id=? AND version=3",
                            Long.class,
                            UUID.fromString(id)))
                    .isEqualTo(1);
        }
    }

    @Test
    void bootstrapOrderedFieldsHrRevocationAndEventDeduplication() throws Exception {
        EventEnvelope<JsonNode> profile = EventEnvelope.of(
                "employee.profile.updated", json.valueToTree(Map.of("userId", 107, "reportingManagerUserId", 207)));
        projections.apply(profile);
        projections.apply(profile);
        scope.reconcile(107, new Models.EmployeeScope(true, 3L, 999L, at));
        scope.reconcile(207, new Models.EmployeeScope(true, 3L, null, at));
        assertThat(scope.eligible(107, 207)).isTrue();
        scope.reconcile(307, new Models.EmployeeScope(true, 4L, null, at));
        scope.reviewer(307, new Models.Reviewer(Models.Role.HR_MANAGER, at));
        assertThat(scope.eligible(107, 307)).isTrue();
        assertThat(scope.eligible(307, 307)).isFalse();
        scope.reviewer(307, new Models.Reviewer(null, at.plusSeconds(1)));
        scope.reviewer(307, new Models.Reviewer(Models.Role.HR_MANAGER, at));
        assertThat(scope.eligible(107, 307)).isFalse();
        scope.reconcile(207, new Models.EmployeeScope(false, 3L, null, at.plusSeconds(1)));
        assertThat(scope.eligible(107, 207)).isFalse();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM processed_event WHERE event_id=?", Long.class, profile.eventId()))
                .isEqualTo(1);
    }
}
