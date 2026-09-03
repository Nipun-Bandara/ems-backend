-- The templates this service ships with.
--
-- Seeded here rather than from code so a fresh database can send mail the
-- moment the migrations finish: EventConsumer looks the welcome template up by
-- key and fails the delivery if it is not there.
--
-- template_id is left to the identity column: nothing references these rows by
-- a fixed id. ON CONFLICT keys on template_key, which
-- uk_notification_template_key makes unique, so re-running this against a
-- populated table is a no-op -- and so an operator's later edit to the wording
-- is not undone by a redeploy.
--
-- Placeholders are {{name}} and are filled from the model the handler passes;
-- one that nothing provides fails the send rather than mailing the literal
-- text, so do not add a placeholder here without adding it there too.

INSERT INTO notification_template (template_key, subject, body, created_at) VALUES
    (
        'user.welcome',
        'Welcome to EMS, {{username}}',
        E'Hi {{username}},\n\n'
            || E'Your EMS account is ready. Sign in with this email address ({{email}}) and the password you chose when you registered.\n\n'
            || E'If you did not create this account, please tell your administrator.\n\n'
            || E'-- The EMS team\n',
        now()
    )
ON CONFLICT (template_key) DO NOTHING;
