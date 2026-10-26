CREATE TABLE employee_profile (
    user_id bigint NOT NULL PRIMARY KEY,
    email varchar(255) NOT NULL,
    username varchar(255) NOT NULL,
    status varchar(255) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL
);
