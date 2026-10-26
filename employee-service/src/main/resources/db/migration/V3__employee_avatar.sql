ALTER TABLE employee_profile
    ADD COLUMN avatar_object_key varchar(255),
    ADD COLUMN pending_avatar_key varchar(255),
    ADD COLUMN pending_avatar_type varchar(100),
    ADD COLUMN pending_avatar_size bigint,
    ADD COLUMN pending_avatar_expires_at timestamptz;
