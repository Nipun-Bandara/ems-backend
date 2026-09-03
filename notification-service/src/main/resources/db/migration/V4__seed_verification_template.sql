-- The verification mail, which is now what a registration causes. The welcome
-- mail seeded in V2 is unchanged but no longer sent on 'user.registered': it
-- moved to 'user.verified', so it arrives once the address is confirmed rather
-- than to an address nobody has shown they can read.
--
-- Same conventions as V2: identity column for template_id, ON CONFLICT on
-- template_key so a redeploy does not undo an operator's edit to the wording,
-- and {{placeholders}} filled from the model EventConsumer passes -- one that
-- nothing provides fails the send, so do not add a placeholder here without
-- adding it there too.

INSERT INTO notification_template (template_key, subject, body, created_at) VALUES
    (
        'user.verify-email',
        'Confirm your email address, {{username}}',
        E'Hi {{username}},\n\n'
            || E'Confirm that {{email}} is your address to finish setting up your EMS account:\n\n'
            || E'{{verifyUrl}}\n\n'
            || E'The link works once and expires in 24 hours. If it has already expired, ask for a new one from the sign-in page.\n\n'
            || E'If you did not create this account, ignore this email -- nothing was set up without this step.\n\n'
            || E'-- The EMS team\n',
        now()
    )
ON CONFLICT (template_key) DO NOTHING;
