package com.ems.notification_service.timesheet;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.sql.Date;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class TimesheetNotificationDeliveryTest {
    @Test
    void buildsASerializableMessageAndMarksOnlySuccessfulDelivery() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        var sender = mock(JavaMailSender.class);
        UUID workflow = fixture(jdbc, sender);
        var message = sender.createMimeMessage();
        new TimesheetNotificationDelivery(jdbc, sender, manager(), "no-reply@example.test").recover();
        verify(sender).send(message);
        assertThat(message.getMessageID()).isEqualTo("<timesheet-" + workflow + "@ems.local>");
        var bytes = new ByteArrayOutputStream();
        message.writeTo(bytes);
        assertThat(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("2026-12-15");
        verify(jdbc).update(contains("SET sent_at=now()"), eq(workflow));
        verify(jdbc, never()).update(contains("next_attempt=now()"), any(), any());
    }

    @Test
    void smtpFailurePersistsRetryWithoutMarkingSent() {
        var jdbc = mock(JdbcTemplate.class);
        var sender = mock(JavaMailSender.class);
        UUID workflow = fixture(jdbc, sender);
        doThrow(new MailSendException("SMTP offline")).when(sender).send(any(MimeMessage.class));
        new TimesheetNotificationDelivery(jdbc, sender, manager(), "no-reply@example.test").recover();
        verify(jdbc).update(contains("next_attempt=now()"), eq("MailSendException"), eq(workflow));
        verify(jdbc, never()).update(contains("SET sent_at=now()"), any(UUID.class));
    }

    private UUID fixture(JdbcTemplate jdbc, JavaMailSender sender) {
        UUID id = UUID.randomUUID();
        Map<String, Object> row = Map.of(
                "decision_id",
                id,
                "employee_id",
                1L,
                "sheet_id",
                UUID.randomUUID(),
                "week_start",
                Date.valueOf("2026-12-15"),
                "outcome",
                "REJECTED",
                "reason",
                "Please fix the description");
        when(jdbc.queryForList(startsWith("SELECT * FROM timesheet_notification_delivery")))
                .thenReturn(List.of(row), List.of());
        when(jdbc.queryForList(startsWith("SELECT email"), eq(String.class), eq(1L)))
                .thenReturn(List.of("employee@example.test"));
        when(jdbc.queryForMap(startsWith("SELECT subject,body"), eq("timesheet.rejected")))
                .thenReturn(Map.of(
                        "subject", "Timesheet rejected", "body", "Rejected {{weekStart}} {{reason}} {{sheetId}}"));
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        return id;
    }

    private PlatformTransactionManager manager() {
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return manager;
    }
}
