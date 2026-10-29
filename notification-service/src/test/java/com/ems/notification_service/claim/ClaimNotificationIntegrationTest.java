package com.ems.notification_service.claim;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.ems.common.event.EventEnvelope;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
        properties = {
            "ems.messaging.enabled=false",
            "management.health.mail.enabled=false",
            "ems.storage.enabled=false",
            "ems.claim-notification.poll-ms=3600000",
            "ems.timesheet-notification.poll-ms=3600000",
            "ems.leave-notification.poll-ms=3600000"
        })
@ActiveProfiles("test")
@Testcontainers
class ClaimNotificationIntegrationTest {
    @Container
    static final PostgreSQLContainer DB = new PostgreSQLContainer("postgres:17");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", DB::getJdbcUrl);
        p.add("spring.datasource.username", DB::getUsername);
        p.add("spring.datasource.password", DB::getPassword);
    }

    @Autowired
    ClaimNotificationConsumer consumer;

    @Autowired
    ClaimNotificationDelivery delivery;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    JsonMapper json;

    @MockitoBean
    JavaMailSender sender;

    @Test
    void duplicateDecisionsMissingContactSmtpFailureAndRecovery() throws Exception {
        UUID claim = UUID.randomUUID();
        Map<String, Object> payload = Map.of(
                "id",
                claim,
                "employeeId",
                801,
                "total",
                "0.30",
                "currency",
                "LKR",
                "status",
                "REJECTED",
                "history",
                List.of(Map.of("action", "REJECTED", "reason", "Please correct amount")));
        EventEnvelope<JsonNode> event = EventEnvelope.of("claim.rejected", json.valueToTree(payload));
        consumer.accept(event);
        consumer.accept(event);
        consumer.accept(EventEnvelope.of("claim.rejected", json.valueToTree(payload)));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM claim_notification_delivery WHERE claim_id=?", Long.class, claim))
                .isEqualTo(1);
        UUID decision = jdbc.queryForObject(
                "SELECT delivery_id FROM claim_notification_delivery WHERE claim_id=?", UUID.class, claim);
        delivery.recover();
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM claim_notification_delivery WHERE delivery_id=?",
                        Integer.class,
                        decision))
                .isEqualTo(1);
        verifyNoInteractions(sender);
        consumer.accept(EventEnvelope.of(
                "user.verified",
                json.valueToTree(Map.of(
                        "userId", 801, "email", "employee@example.test", "verifiedAt", "2026-10-01T00:00:00Z"))));
        consumer.accept(EventEnvelope.of(
                "user.verified",
                json.valueToTree(
                        Map.of("userId", 801, "email", "stale@example.test", "verifiedAt", "2026-09-01T00:00:00Z"))));
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("offline")).doNothing().when(sender).send(any(MimeMessage.class));
        due(decision);
        delivery.recover();
        assertThat(jdbc.queryForObject(
                        "SELECT sent_at IS NULL FROM claim_notification_delivery WHERE delivery_id=?",
                        Boolean.class,
                        decision))
                .isTrue();
        due(decision);
        delivery.recover();
        delivery.recover();
        var messages = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(messages.capture());
        for (var message : messages.getAllValues()) {
            assertThat(message.getMessageID()).isEqualTo("<claim-" + decision + "@ems.local>");
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("employee@example.test");
            assertThat(message.getContent().toString()).contains("Please correct amount");
        }
        assertThat(jdbc.queryForObject(
                        "SELECT attempts FROM claim_notification_delivery WHERE delivery_id=?",
                        Integer.class,
                        decision))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT sent_at IS NOT NULL FROM claim_notification_delivery WHERE delivery_id=?",
                        Boolean.class,
                        decision))
                .isTrue();
    }

    @Test
    void submittedAndApprovedEachDeliverOnceWithReimbursementWording() throws Exception {
        UUID claim = UUID.randomUUID();
        consumer.accept(EventEnvelope.of(
                "user.verified",
                json.valueToTree(
                        Map.of("userId", 802, "email", "second@example.test", "verifiedAt", "2026-10-01T00:00:00Z"))));
        for (String status : List.of("SUBMITTED", "APPROVED")) {
            var payload = Map.of(
                    "id",
                    claim,
                    "employeeId",
                    802,
                    "total",
                    "0.30",
                    "currency",
                    "LKR",
                    "status",
                    status,
                    "history",
                    List.of());
            EventEnvelope<JsonNode> event =
                    EventEnvelope.of("claim." + status.toLowerCase(Locale.ROOT), json.valueToTree(payload));
            consumer.accept(event);
            consumer.accept(event);
            consumer.accept(EventEnvelope.of(event.type(), json.valueToTree(payload)));
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM claim_notification_delivery WHERE claim_id=?", Long.class, claim))
                .isEqualTo(2);
        when(sender.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        delivery.recover();
        delivery.recover();
        var messages = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender, times(2)).send(messages.capture());
        assertThat(messages.getAllValues().stream()
                        .map(m -> {
                            try {
                                return m.getContent().toString();
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                        .toList())
                .anySatisfy(
                        body -> assertThat(body).contains("approved for reimbursement", "does not confirm payment"));
    }

    private void due(UUID decision) {
        jdbc.update("UPDATE claim_notification_delivery SET next_attempt=now() WHERE delivery_id=?", decision);
    }
}
