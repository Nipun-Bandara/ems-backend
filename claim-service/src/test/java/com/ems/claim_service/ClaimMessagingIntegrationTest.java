package com.ems.claim_service;

import static org.assertj.core.api.Assertions.*;

import com.ems.common.event.EventEnvelope;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = {"ems.storage.enabled=false", "ems.outbox.poll-interval=200ms"})
@ActiveProfiles("test")
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(
        classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ClaimMessagingIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
        p.add("spring.rabbitmq.host", RABBIT::getHost);
        p.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        p.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        p.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Autowired
    ClaimService service;

    @Autowired
    EmployeeScopeStore scope;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin broker;

    @Autowired
    JsonMapper json;

    @Test
    void projectionsAndBothTransitionsTravelThroughTheBroker() throws Exception {
        broker.declareQueue(new org.springframework.amqp.core.Queue("claim.test-events", true));
        broker.declareBinding(
                new Binding("claim.test-events", Binding.DestinationType.QUEUE, "ems.events", "claim.*", null));
        var now = Instant.now().minusSeconds(5);
        scope.reconcile(901, new Models.EmployeeScope(true, 1L, 902L, now));
        scope.reconcile(902, new Models.EmployeeScope(true, 1L, null, now));
        // Profile and verification can arrive before their bootstrap snapshots.
        rabbit.convertAndSend(
                "ems.events",
                "user.verified",
                EventEnvelope.of(
                        "user.verified",
                        Map.of("userId", 903, "verifiedAt", now.toString(), "email", "fixture@example.test")));
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!scope.active(903) && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(scope.active(903)).isTrue();
        var draft = service.create(901, new Models.Create("LKR"));
        var saved = service.save(
                draft.id(),
                901,
                new Models.Save(
                        0L,
                        List.of(
                                new Models.Line("Travel", LocalDate.of(2026, 10, 29), "Train", "LKR", "0.10"),
                                new Models.Line("Meals", LocalDate.of(2026, 10, 29), "Lunch", "LKR", "0.20"))));
        jdbc.update(
                "INSERT INTO claim_receipt(id,claim_id,owner_id,object_key,filename,content_type,size,expires_at,finalized) VALUES (?,?,?,?,?,'application/pdf',1,now(),true)",
                UUID.randomUUID(),
                saved.id(),
                901,
                "test/" + UUID.randomUUID(),
                "fixture.pdf");
        service.submit(saved.id(), 901, new Models.Transition(1L, null));
        service.submit(saved.id(), 901, new Models.Transition(1L, null));
        service.decide(saved.id(), 902, new Models.Transition(2L, null), false);
        Set<String> types = new HashSet<>();
        for (int i = 0; i < 2; i++) {
            var message = rabbit.receive("claim.test-events", 10000);
            assertThat(message).isNotNull();
            var event = json.readTree(message.getBody());
            types.add(event.path("type").asText());
            assertThat(event.path("payload").path("total").asText()).isEqualTo("0.30");
            assertThat(event.path("payload").path("lines").size()).isEqualTo(2);
        }
        assertThat(types).containsExactlyInAnyOrder("claim.submitted", "claim.approved");
        assertThat(rabbit.receive("claim.test-events", 600)).isNull();
    }
}
