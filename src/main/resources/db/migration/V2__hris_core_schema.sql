-- HRIS core schema (Milestone 2, Task 2.1). Source of design: docs/database-design.md §3.1
-- Enumerations are VARCHAR + CHECK and must match the Java enums in com.mimocodes.hrisrag.data.*

CREATE TABLE department (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name_en     VARCHAR(120) NOT NULL,
    name_ar     VARCHAR(120) NOT NULL
);

CREATE TABLE employee (
    id              BIGSERIAL PRIMARY KEY,
    employee_code   VARCHAR(20)  NOT NULL UNIQUE,
    full_name_en    VARCHAR(160) NOT NULL,
    full_name_ar    VARCHAR(160) NOT NULL,
    email           VARCHAR(200) NOT NULL UNIQUE,
    department_id   BIGINT       NOT NULL REFERENCES department(id),
    manager_id      BIGINT       REFERENCES employee(id),
    job_title       VARCHAR(120) NOT NULL,
    employment_type VARCHAR(20)  NOT NULL CHECK (employment_type IN ('FULL_TIME','PART_TIME','CONTRACTOR','INTERN')),
    hire_date       DATE         NOT NULL,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('ACTIVE','ON_LEAVE','TERMINATED')),
    CONSTRAINT employee_not_own_manager CHECK (manager_id IS NULL OR manager_id <> id)
);
CREATE INDEX employee_department_idx ON employee(department_id);
CREATE INDEX employee_manager_idx    ON employee(manager_id);

-- Restricted (HR_ADMIN only). Never embedded or indexed for RAG.
CREATE TABLE employee_compensation (
    employee_id     BIGINT PRIMARY KEY REFERENCES employee(id),
    monthly_salary  NUMERIC(12,2) NOT NULL CHECK (monthly_salary > 0),
    currency        VARCHAR(3)    NOT NULL DEFAULT 'EGP' CHECK (char_length(currency) = 3),
    effective_from  DATE          NOT NULL
);

CREATE TABLE attendance_record (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT      NOT NULL REFERENCES employee(id),
    work_date       DATE        NOT NULL,
    check_in        TIMESTAMPTZ,
    check_out       TIMESTAMPTZ,
    minutes_late    INT         NOT NULL DEFAULT 0 CHECK (minutes_late >= 0),
    status          VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT','LATE','ABSENT','REMOTE','ON_LEAVE','HOLIDAY')),
    CONSTRAINT attendance_one_per_day UNIQUE (employee_id, work_date),
    CONSTRAINT attendance_checkout_after_checkin CHECK (check_out IS NULL OR check_in IS NULL OR check_out > check_in)
);

CREATE TABLE leave_type (
    id                  BIGSERIAL PRIMARY KEY,
    code                VARCHAR(20)  NOT NULL UNIQUE,
    name_en             VARCHAR(80)  NOT NULL,
    name_ar             VARCHAR(80)  NOT NULL,
    default_annual_days NUMERIC(5,1) NOT NULL CHECK (default_annual_days >= 0)
);

CREATE TABLE leave_balance (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT       NOT NULL REFERENCES employee(id),
    leave_type_id   BIGINT       NOT NULL REFERENCES leave_type(id),
    year            SMALLINT     NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    entitled_days   NUMERIC(5,1) NOT NULL CHECK (entitled_days >= 0),
    used_days       NUMERIC(5,1) NOT NULL DEFAULT 0 CHECK (used_days >= 0),
    pending_days    NUMERIC(5,1) NOT NULL DEFAULT 0 CHECK (pending_days >= 0),
    CONSTRAINT leave_balance_one_per_year UNIQUE (employee_id, leave_type_id, year)
);

CREATE TABLE leave_request (
    id              BIGSERIAL PRIMARY KEY,
    request_code    VARCHAR(20)  NOT NULL UNIQUE,
    employee_id     BIGINT       NOT NULL REFERENCES employee(id),
    leave_type_id   BIGINT       NOT NULL REFERENCES leave_type(id),
    start_date      DATE         NOT NULL,
    end_date        DATE         NOT NULL,
    days            NUMERIC(5,1) NOT NULL CHECK (days > 0),
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    approver_id     BIGINT       REFERENCES employee(id),
    reason          VARCHAR(500),
    submitted_at    TIMESTAMPTZ  NOT NULL,
    decided_at      TIMESTAMPTZ,
    CONSTRAINT leave_request_dates CHECK (end_date >= start_date),
    CONSTRAINT leave_request_decision CHECK ((status IN ('APPROVED','REJECTED')) = (decided_at IS NOT NULL))
);
CREATE INDEX leave_request_employee_idx ON leave_request(employee_id, start_date);

CREATE TABLE meeting (
    id                BIGSERIAL PRIMARY KEY,
    meeting_code      VARCHAR(20)  NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    organizer_id      BIGINT       NOT NULL REFERENCES employee(id),
    starts_at         TIMESTAMPTZ  NOT NULL,
    ends_at           TIMESTAMPTZ  NOT NULL,
    location          VARCHAR(120),
    requires_approval BOOLEAN      NOT NULL DEFAULT FALSE,
    status            VARCHAR(20)  NOT NULL CHECK (status IN ('SCHEDULED','PENDING_APPROVAL','CANCELLED','COMPLETED')),
    CONSTRAINT meeting_ends_after_start CHECK (ends_at > starts_at)
);
CREATE INDEX meeting_starts_idx ON meeting(starts_at);

CREATE TABLE meeting_invitation (
    meeting_id      BIGINT      NOT NULL REFERENCES meeting(id) ON DELETE CASCADE,
    employee_id     BIGINT      NOT NULL REFERENCES employee(id),
    response        VARCHAR(20) NOT NULL CHECK (response IN ('PENDING','ACCEPTED','DECLINED','TENTATIVE')),
    attended        BOOLEAN,
    PRIMARY KEY (meeting_id, employee_id)
);
CREATE INDEX meeting_invitation_employee_idx ON meeting_invitation(employee_id);

CREATE TABLE employee_reward (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT       NOT NULL REFERENCES employee(id),
    reward_type     VARCHAR(30)  NOT NULL CHECK (reward_type IN ('SPOT_AWARD','EMPLOYEE_OF_MONTH','BONUS','CERTIFICATE','PROMOTION_RECOGNITION')),
    awarded_on      DATE         NOT NULL,
    awarded_by_id   BIGINT       REFERENCES employee(id),
    description     VARCHAR(500) NOT NULL
);
CREATE INDEX employee_reward_employee_idx ON employee_reward(employee_id);

-- Restricted (HR_ADMIN, or the employee's direct manager).
CREATE TABLE disciplinary_record (
    id              BIGSERIAL PRIMARY KEY,
    employee_id     BIGINT       NOT NULL REFERENCES employee(id),
    action_type     VARCHAR(30)  NOT NULL CHECK (action_type IN ('VERBAL_WARNING','WRITTEN_WARNING','FINAL_WARNING','SUSPENSION','DEDUCTION')),
    issued_on       DATE         NOT NULL,
    issued_by_id    BIGINT       REFERENCES employee(id),
    reason          VARCHAR(500) NOT NULL
);
CREATE INDEX disciplinary_record_employee_idx ON disciplinary_record(employee_id);

CREATE TABLE announcement (
    id                BIGSERIAL PRIMARY KEY,
    announcement_code VARCHAR(20)  NOT NULL UNIQUE,
    title             VARCHAR(200) NOT NULL,
    body              TEXT         NOT NULL,
    language          VARCHAR(5)   NOT NULL CHECK (language IN ('en','ar')),
    scope             VARCHAR(20)  NOT NULL CHECK (scope IN ('COMPANY','DEPARTMENT')),
    department_id     BIGINT       REFERENCES department(id),
    published_at      TIMESTAMPTZ  NOT NULL,
    expires_at        TIMESTAMPTZ,
    CONSTRAINT announcement_scope_department CHECK ((scope = 'DEPARTMENT') = (department_id IS NOT NULL)),
    CONSTRAINT announcement_expiry CHECK (expires_at IS NULL OR expires_at > published_at)
);
CREATE INDEX announcement_published_idx ON announcement(published_at);
