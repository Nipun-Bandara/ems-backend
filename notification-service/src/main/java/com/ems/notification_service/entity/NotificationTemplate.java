package com.ems.notification_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * The subject and body an outgoing email is rendered from, keyed by
 * {@link #getTemplateKey() template key} rather than by id so that a handler asks for
 * {@code user.welcome} and does not have to know which row that is.
 *
 * <p>In the database rather than in a resource file because the wording of a customer-facing
 * email is not a code change: it can be corrected without a redeploy, and the row that
 * produced a given mail is still there afterwards to look at.
 *
 * <p>Both fields go through {@link com.ems.notification_service.mail.TemplateRenderer}, so a
 * placeholder is as usable in the subject line as in the body.
 */
@Entity
@Table(name = "notification_template")
public class NotificationTemplate {

    /** Key of the template sent to a user who has just registered. */
    public static final String WELCOME_KEY = "user.welcome";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "template_id")
    private Long templateId;

    @Column(name = "template_key", nullable = false, unique = true)
    private String templateKey;

    @Column(name = "subject", nullable = false)
    private String subject;

    /**
     * Mapped as {@code text}: an email body has no natural length limit, and a
     * {@code varchar(n)} here would only ever be a number someone had to guess.
     */
    @JdbcTypeCode(SqlTypes.LONG32VARCHAR)
    @Column(name = "body", nullable = false)
    private String body;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected NotificationTemplate() {
        // for JPA
    }

    public Long getTemplateId() {
        return templateId;
    }

    public String getTemplateKey() {
        return templateKey;
    }

    public String getSubject() {
        return subject;
    }

    public String getBody() {
        return body;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
