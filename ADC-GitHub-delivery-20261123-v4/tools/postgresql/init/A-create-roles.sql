-- REVIEWED CANDIDATE. Actual execution requires separate user approval.
\set ON_ERROR_STOP on
-- Do not let caller-supplied savepoint recovery weaken transaction failure semantics.
\set ON_ERROR_ROLLBACK off
\set AUTOCOMMIT on
\set adc_expected_stage A
\ir checks/assert-approval.sql

BEGIN;
\ir checks/assert-session.sql

DO $check$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname IN (
            'adc_dev_app', 'adc_test_app',
            'adc_dev_migrator', 'adc_test_migrator'
        )
    ) OR EXISTS (
        SELECT 1 FROM pg_database WHERE datname IN ('adc_dev', 'adc_test')
    ) THEN
        RAISE EXCEPTION 'A requires all ADC role and database names to be absent; do not overwrite or retry automatically';
    END IF;
END;
$check$;

CREATE ROLE adc_dev_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE adc_test_app NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE adc_dev_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;
CREATE ROLE adc_test_migrator NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
    NOINHERIT NOREPLICATION NOBYPASSRLS;

\ir checks/assert-roles.sql
COMMIT;
\echo ADC_A_PASS_ROLES_NOLOGIN
