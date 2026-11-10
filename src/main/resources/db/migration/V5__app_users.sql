-- Simulated application users (Milestone 6, Task 6.1). Identity for the API comes ONLY from these authenticated users.
--
-- * password_hash is BCrypt, written at startup from the APP_DEMO_PASSWORD environment variable (DemoUserProvisioner).
--   It is NULL here, so no credential is ever stored in a migration or in Git; a NULL hash means the user cannot log in.
-- * employee_code is not a foreign key on purpose: the synthetic HRIS seeder replaces all employee rows, and login
--   accounts must survive a reseed. The codes match the fixed benchmark anchors (docs/synthetic-dataset.md).
-- * max_access_level is the highest document classification the user may read (PUBLIC < INTERNAL < CONFIDENTIAL).
CREATE TABLE app_user (
    id               BIGSERIAL    PRIMARY KEY,
    username         VARCHAR(40)  NOT NULL UNIQUE CHECK (username ~ '^[a-z][a-z0-9_-]{1,39}$'),
    employee_code    VARCHAR(20)  NOT NULL UNIQUE,
    role             VARCHAR(20)  NOT NULL CHECK (role IN ('EMPLOYEE','MANAGER','HR_ADMIN')),
    department_code  VARCHAR(10)  NOT NULL,
    max_access_level VARCHAR(20)  NOT NULL CHECK (max_access_level IN ('PUBLIC','INTERNAL','CONFIDENTIAL')),
    password_hash    VARCHAR(100),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO app_user (username, employee_code, role, department_code, max_access_level) VALUES
    ('employee',   'E-1001',  'EMPLOYEE', 'D-ENG', 'INTERNAL'),      -- Omar Samir, the default benchmark asker
    ('qa',         'E-1002',  'EMPLOYEE', 'D-ENG', 'INTERNAL'),      -- Laila Hassan
    ('manager',    'E-1000',  'MANAGER',  'D-ENG', 'INTERNAL'),      -- Khaled Mansour, manager of E-1001/E-1002
    ('hr',         'E-1003',  'HR_ADMIN', 'D-HR',  'CONFIDENTIAL'),  -- Mona Adel, may read CONFIDENTIAL documents
    ('finance',    'E-1004',  'EMPLOYEE', 'D-FIN', 'INTERNAL'),      -- Youssef Nabil, cross-department tests
    ('contractor', 'CT-0001', 'EMPLOYEE', 'D-OPS', 'PUBLIC');        -- access level below the role default
