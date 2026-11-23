-- REVIEWED CANDIDATE. Actual execution requires separate user approval.
-- NEVER use --single-transaction/-1; CREATE DATABASE cannot run in a transaction.
\set ON_ERROR_STOP on
\set AUTOCOMMIT on
\set adc_expected_stage B1
\ir checks/assert-approval.sql
\ir checks/assert-session.sql
\ir checks/assert-roles.sql

DO $check$
DECLARE
    template_row record;
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_database WHERE datname IN ('adc_dev', 'adc_test')
    ) THEN
        RAISE EXCEPTION 'B1 requires both database names absent; partial success must be reviewed, not deleted or retried';
    END IF;
    SELECT * INTO STRICT template_row FROM pg_database WHERE datname = 'template0';
    IF pg_encoding_to_char(template_row.encoding) <> 'UTF8'
       OR template_row.datlocprovider <> 'c'
       OR template_row.datcollate <> 'Chinese (Simplified)_China.936'
       OR template_row.datctype <> 'Chinese (Simplified)_China.936'
       OR template_row.datlocale IS NOT NULL
       OR NOT template_row.datistemplate
       OR template_row.datallowconn THEN
        RAISE EXCEPTION 'template0 differs from the reviewed Windows locale/template baseline';
    END IF;
END;
$check$;

CREATE DATABASE adc_dev OWNER postgres TEMPLATE template0 ENCODING 'UTF8'
    LOCALE_PROVIDER libc
    LC_COLLATE 'Chinese (Simplified)_China.936'
    LC_CTYPE 'Chinese (Simplified)_China.936'
    ALLOW_CONNECTIONS false;

CREATE DATABASE adc_test OWNER postgres TEMPLATE template0 ENCODING 'UTF8'
    LOCALE_PROVIDER libc
    LC_COLLATE 'Chinese (Simplified)_China.936'
    LC_CTYPE 'Chinese (Simplified)_China.936'
    ALLOW_CONNECTIONS false;

\ir checks/assert-roles.sql
\ir checks/assert-closed-databases.sql
\ir checks/assert-initial-acls.sql
\echo ADC_B1_PASS_DATABASES_CLOSED_INITIAL_ACL
