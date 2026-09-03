-- The two mails a password reset causes: the link that starts it, and the
-- notice that says it finished.
--
-- Same conventions as V2 and V4: identity column for template_id, ON CONFLICT
-- on template_key so a redeploy does not undo an operator's edit to the
-- wording, and {{placeholders}} filled from the model EventConsumer passes --
-- one that nothing provides fails the send, so do not add a placeholder here
-- without adding it there too.

INSERT INTO notification_template (template_key, subject, body, created_at) VALUES
    (
        'user.password-reset',
        'Reset your EMS password, {{username}}',
        E'Hi {{username}},\n\n'
            || E'Someone asked to reset the password for the EMS account on {{email}}. Choose a new one here:\n\n'
            || E'{{resetUrl}}\n\n'
            || E'The link works once and expires in one hour.\n\n'
            -- The reassurance matters as much as the link: most people who get
            -- this without asking for it are wondering whether they have to do
            -- something. They do not -- an unused link simply expires.
            || E'If you did not ask for this, you can ignore this email. Your password will not change unless you use the link above, and nobody can sign in with this email alone.\n\n'
            || E'-- The EMS team\n',
        now()
    ),
    (
        'user.password-changed',
        'Your EMS password was changed',
        E'Hi {{username}},\n\n'
            || E'The password for the EMS account on {{email}} was just changed, and anything still signed in has been signed out.\n\n'
            || E'If that was you, there is nothing to do.\n\n'
            -- No link, on purpose. This mail exists for the case where the
            -- reset was not the owner's, and a security warning that asks the
            -- reader to click something is the same shape as the attack it is
            -- warning about. Sending them to a page they already know how to
            -- reach costs nothing and cannot be spoofed.
            || E'If it was not you, go to the EMS sign-in page and request a password reset straight away to take the account back, then contact your administrator.\n\n'
            || E'-- The EMS team\n',
        now()
    )
ON CONFLICT (template_key) DO NOTHING;
