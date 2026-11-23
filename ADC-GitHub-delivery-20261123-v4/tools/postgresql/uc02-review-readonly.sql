-- Approval candidate only. Use psql -X against a newly approved loopback test instance.
-- Verify pg_controldata and process ownership outside the connection first.
-- Required variables: expected_system_id, expected_database, expected_port,
-- expected_login, expected_version, expected_data_directory, observed_role.
\set ON_ERROR_STOP on
BEGIN READ ONLY;

SELECT COALESCE(current_database() = :'expected_database'
    AND current_database() LIKE 'adc_uc02_iso_%'
    AND session_user = :'expected_login' AND current_user = session_user
    AND inet_server_addr() = '127.0.0.1'::inet
    AND inet_server_port() = :'expected_port'::integer
    AND current_setting('server_version_num') = :'expected_version'
    AND current_setting('data_directory') = :'expected_data_directory'
    AND system_identifier::text = :'expected_system_id', false) AS target_ok
FROM pg_control_system() \gset
\if :target_ok
\else
SELECT CAST('UC02_ISOLATION_IDENTITY_MISMATCH' AS integer);
\quit
\endif

SELECT current_database(), session_user, current_user, inet_server_port(),
       current_setting('server_version_num'), current_setting('transaction_read_only');

-- The login running this script is an observer, not an application identity.
-- Actual-role positive and negative probes require separately approved sessions.
WITH RECURSIVE membership AS (
    SELECT oid AS roleid FROM pg_roles WHERE rolname = :'observed_role'
    UNION
    SELECT m.roleid FROM membership r JOIN pg_auth_members m ON m.member = r.roleid
)
SELECT member.rolname AS member, parent.rolname AS parent, m.inherit_option, m.set_option, m.admin_option
FROM pg_auth_members m
JOIN membership r ON r.roleid = m.member
JOIN pg_roles member ON member.oid = m.member
JOIN pg_roles parent ON parent.oid = m.roleid
ORDER BY 1, 2;

SELECT rolname, rolsuper, rolinherit, rolcreaterole, rolcreatedb, rolbypassrls,
       has_database_privilege(rolname, current_database(), 'CONNECT') AS can_connect,
       has_schema_privilege(rolname, 'adc', 'USAGE') AS schema_usage,
       has_schema_privilege(rolname, 'adc', 'CREATE') AS schema_create
FROM pg_roles WHERE rolname = :'observed_role';

SELECT c.relname, pg_get_userbyid(c.relowner) AS owner,
       has_table_privilege(:'observed_role', c.oid, 'SELECT') AS can_select,
       has_table_privilege(:'observed_role', c.oid, 'INSERT') AS can_insert,
       has_table_privilege(:'observed_role', c.oid, 'UPDATE') AS can_update,
       has_table_privilege(:'observed_role', c.oid, 'DELETE') AS can_delete
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'adc' AND c.relname IN
    ('dentist', 'patient', 'reception_schedule_version', 'project_duration_version',
     'appointment', 'operation_audit_event', 'extension_operation_audit_event')
ORDER BY c.relname;

SELECT c.relname, a.attname, format_type(a.atttypid, a.atttypmod) AS data_type, a.attnotnull,
       a.attidentity, pg_get_expr(d.adbin, d.adrelid) AS default_expression,
       has_column_privilege(:'observed_role', c.oid, a.attnum, 'SELECT') AS can_select,
       has_column_privilege(:'observed_role', c.oid, a.attnum, 'INSERT') AS can_insert,
       has_column_privilege(:'observed_role', c.oid, a.attnum, 'UPDATE') AS can_update
FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
WHERE n.nspname = 'adc' AND c.relname IN
    ('dentist', 'patient', 'reception_schedule_version', 'project_duration_version',
     'appointment', 'operation_audit_event')
  AND a.attnum > 0 AND NOT a.attisdropped
ORDER BY c.relname, a.attnum;

SELECT c.relname, k.conname, pg_get_constraintdef(k.oid) AS definition
FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'adc' AND c.relname IN ('appointment', 'operation_audit_event')
ORDER BY c.relname, k.conname;

SELECT pid, application_name, wait_event_type, wait_event, pg_blocking_pids(pid) AS blocking_pids
FROM pg_stat_activity
WHERE datname = current_database() AND left(application_name, 12) = 'uc02-review-'
ORDER BY pid;

ROLLBACK;
