package com.ems.notification_service.mail;

import com.ems.notification_service.entity.NotificationTemplate;
import com.ems.notification_service.repository.NotificationTemplateRepository;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Sends one stored template to one address: looks the template up by key, renders its subject
 * and body against the given model, and hands the result to SMTP.
 *
 * <p>Nothing here is caught. A missing template, an unresolved placeholder or an unreachable
 * mail server all propagate to the listener, which is what puts the delivery through the
 * retry and parking path in ems-common instead of dropping the mail on the floor.
 */
@Service
public class TemplatedMailer {

    private static final Logger log = LoggerFactory.getLogger(TemplatedMailer.class);

    private final NotificationTemplateRepository templates;
    private final JavaMailSender mailSender;
    private final String from;

    public TemplatedMailer(
            NotificationTemplateRepository templates,
            JavaMailSender mailSender,
            @Value("${app.mail.from}") String from) {
        this.templates = templates;
        this.mailSender = mailSender;
        this.from = from;
    }

    /**
     * @param templateKey which template to send, for example
     *     {@link NotificationTemplate#WELCOME_KEY}
     * @param to the recipient address
     * @param model values for the template's {@code {{placeholder}}} markers
     */
    public void send(String templateKey, String to, Map<String, String> model) {
        NotificationTemplate template = templates
                .findByTemplateKey(templateKey)
                .orElseThrow(() -> new IllegalStateException(
                        "No notification_template row with template_key '%s'".formatted(templateKey)));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject(TemplateRenderer.render(template.getSubject(), model));
        message.setText(TemplateRenderer.render(template.getBody(), model));

        mailSender.send(message);
        log.info("Sent '{}' to {}", templateKey, to);
    }
}
