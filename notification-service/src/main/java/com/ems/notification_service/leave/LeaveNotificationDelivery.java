package com.ems.notification_service.leave;

import com.ems.notification_service.mail.TemplateRenderer;
import jakarta.mail.internet.MimeMessage;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class LeaveNotificationDelivery {
    private final JdbcTemplate jdbc;
    private final JavaMailSender sender;
    private final String from;
    private final TransactionTemplate transaction;

    public LeaveNotificationDelivery(
            JdbcTemplate jdbc,
            JavaMailSender sender,
            PlatformTransactionManager manager,
            @Value("${app.mail.from}") String from) {
        this.jdbc = jdbc;
        this.sender = sender;
        this.from = from;
        transaction = new TransactionTemplate(manager);
    }

    @Scheduled(fixedDelayString = "${ems.leave-notification.poll-ms:2000}")
    public void recover() {
        // SKIP LOCKED permits multiple service instances without duplicate concurrent sends.
        for (int i = 0; i < 20; i++) {
            Boolean found = transaction.execute(tx -> {
                var rows = jdbc.queryForList(
                        "SELECT * FROM leave_notification_delivery WHERE sent_at IS NULL AND next_attempt<=now() ORDER BY next_attempt LIMIT 1 FOR UPDATE SKIP LOCKED");
                if (rows.isEmpty()) return false;
                deliver(rows.getFirst());
                return true;
            });
            if (!Boolean.TRUE.equals(found)) return;
        }
    }

    private void deliver(Map<String, Object> row) {
        UUID id = (UUID) row.get("workflow_id");
        try {
            var contacts = jdbc.queryForList(
                    "SELECT email FROM leave_notification_contact WHERE employee_id=?",
                    String.class,
                    row.get("employee_id"));
            if (contacts.isEmpty()) throw new IllegalStateException("Recipient snapshot required");
            var template = jdbc.queryForMap(
                    "SELECT subject,body FROM notification_template WHERE template_key='leave.approved'");
            Map<String, String> model = Map.of(
                    "startDate",
                    row.get("start_date").toString(),
                    "endDate",
                    row.get("end_date").toString(),
                    "requestId",
                    row.get("request_id").toString());
            // A stable Message-ID identifies one logical delivery even across SMTP ambiguity.
            MimeMessage message = sender.createMimeMessage();
            // JavaMailSenderImpl preserves an explicitly supplied Message-ID across saveChanges.
            message.setHeader("Message-ID", "<leave-" + id + "@ems.local>");
            var helper = new MimeMessageHelper(message, "UTF-8");
            helper.setFrom(from);
            helper.setTo(contacts.getFirst());
            helper.setSubject(TemplateRenderer.render((String) template.get("subject"), model));
            helper.setText(TemplateRenderer.render((String) template.get("body"), model));
            sender.send(message);
            jdbc.update(
                    "UPDATE leave_notification_delivery SET sent_at=now(),attempts=attempts+1,last_error=NULL WHERE workflow_id=?",
                    id);
        } catch (Exception failure) {
            // Persist bounded backoff indefinitely; approval state belongs to leave-service.
            jdbc.update(
                    "UPDATE leave_notification_delivery SET attempts=attempts+1,next_attempt=now()+least(300,2*power(2,least(attempts,7))) * interval '1 second',last_error=? WHERE workflow_id=?",
                    failure.getClass().getSimpleName(),
                    id);
        }
    }
}
