-- Read-only acceptance. No connection opening or automatic next stage.
\set ON_ERROR_STOP on
\set AUTOCOMMIT on

BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
\ir checks/assert-session.sql
\ir checks/assert-roles.sql
\ir checks/assert-closed-databases.sql
\ir checks/assert-final-acls.sql

-- Metadata only: no password columns, role settings, or business data.
SELECT datname, pg_get_userbyid(datdba) AS owner,
       pg_encoding_to_char(encoding) AS encoding,
       datlocprovider, datcollate, datctype, datallowconn, datacl
FROM pg_database WHERE datname IN ('adc_dev', 'adc_test') ORDER BY datname;
SELECT rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole,
       rolinherit, rolreplication, rolbypassrls
FROM pg_roles WHERE rolname IN (
    'adc_dev_app', 'adc_test_app', 'adc_dev_migrator', 'adc_test_migrator'
) ORDER BY rolname;

ROLLBACK;
\echo ADC_B3_PASS_READ_ONLY_NOLOGIN_DATABASES_CLOSED
