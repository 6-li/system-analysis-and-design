-- RID-009 approved course extension. Installer must run in one transaction on
-- adc_course_validation:55432 after V001-V008 and the mini intake extension.
-- Deliberately no IF NOT EXISTS: unknown existing objects require inspection.
-- Role creation/password provisioning belongs to the controlled installer.
CREATE TABLE adc_mini.patient_account (
    account_id UUID PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    password_digest VARCHAR(256) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled BOOLEAN NOT NULL DEFAULT FALSE,
    CHECK (username ~ '^[a-z0-9_]{4,32}$'),
    CHECK (password_digest LIKE 'PBKDF2-SHA256$600000$%')
);
CREATE TABLE adc_mini.patient_account_binding (
    account_id UUID PRIMARY KEY REFERENCES adc_mini.patient_account(account_id),
    patient_id BIGINT NOT NULL REFERENCES adc.patient(patient_id),
    verified_staff TEXT NOT NULL CHECK (length(btrim(verified_staff)) > 0),
    verification_basis TEXT NOT NULL CHECK (length(btrim(verification_basis)) > 0),
    verified_request_id UUID NOT NULL REFERENCES adc_mini.preliminary_request(request_id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
REVOKE ALL ON adc_mini.patient_account, adc_mini.patient_account_binding FROM PUBLIC;
-- No grants here until the installer provisions the dedicated credential role
-- and separate intake/read binding privileges. Never grant digest reads to mini_read.
