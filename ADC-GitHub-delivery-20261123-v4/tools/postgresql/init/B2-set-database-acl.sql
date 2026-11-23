-- REVIEWED CANDIDATE. Changes only the two new databases' ACLs.
\set ON_ERROR_STOP on
\set AUTOCOMMIT on
\set adc_expected_stage B2
\ir checks/assert-approval.sql

BEGIN;
\ir checks/assert-session.sql
\ir checks/assert-roles.sql
\ir checks/assert-closed-databases.sql
-- Refuse unknown grants or an already-completed B2 instead of silently rewriting.
\ir checks/assert-initial-acls.sql

REVOKE ALL PRIVILEGES ON DATABASE adc_dev FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE adc_test FROM PUBLIC;
GRANT CONNECT ON DATABASE adc_dev TO adc_dev_app, adc_dev_migrator;
GRANT CONNECT ON DATABASE adc_test TO adc_test_app, adc_test_migrator;
REVOKE ALL PRIVILEGES ON DATABASE adc_dev FROM adc_test_app, adc_test_migrator;
REVOKE ALL PRIVILEGES ON DATABASE adc_test FROM adc_dev_app, adc_dev_migrator;

\ir checks/assert-final-acls.sql
\ir checks/assert-roles.sql
\ir checks/assert-closed-databases.sql
COMMIT;
\echo ADC_B2_PASS_ACL_COMMITTED_CONNECTIONS_STILL_CLOSED
