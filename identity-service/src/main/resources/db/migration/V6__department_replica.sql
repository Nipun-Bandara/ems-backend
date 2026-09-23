CREATE TABLE department_replica (
    department_id bigint NOT NULL,
    department_name varchar(255) NOT NULL,
    PRIMARY KEY (department_id)
);

INSERT INTO department_replica (department_id, department_name)
SELECT department_id, department_name
FROM departments;

ALTER TABLE users
    ADD COLUMN department_name varchar(255);

UPDATE users u
SET department_name = d.department_name
FROM departments d
WHERE u.department_id = d.department_id;

ALTER TABLE users
    DROP CONSTRAINT fksbg59w8q63i0oo53rlgvlcnjq;

DROP TABLE departments;
