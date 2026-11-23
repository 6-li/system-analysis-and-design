-- Strict multiset comparison of exploded ACL rows, including grantor and grant option.
-- NULL means built-in defaults, not an empty ACL. Owner implicit grant options
-- are NOT stored grant-option bits: acldefault stores owner ordinary rights only.
DO $check$
DECLARE
    d record;
    r record;
    allowed_names text[];
BEGIN
    IF (SELECT count(*) FROM pg_database WHERE datname IN ('adc_dev', 'adc_test')) <> 2
       OR (SELECT count(*) FROM pg_roles WHERE rolname IN (
           'adc_dev_app', 'adc_test_app', 'adc_dev_migrator', 'adc_test_migrator')) <> 4 THEN
        RAISE EXCEPTION 'Both ADC databases and all four roles must exist for ACL checks';
    END IF;
    FOR d IN SELECT * FROM pg_database WHERE datname IN ('adc_dev', 'adc_test') LOOP
        IF pg_get_userbyid(d.datdba) IS DISTINCT FROM 'postgres' THEN
            RAISE EXCEPTION 'Unexpected database owner: %', d.datname;
        END IF;
        allowed_names := CASE d.datname
            WHEN 'adc_dev' THEN ARRAY['adc_dev_app', 'adc_dev_migrator']
            ELSE ARRAY['adc_test_app', 'adc_test_migrator']
        END;
        IF EXISTS (
            WITH actual AS (
                SELECT x.grantor, x.grantee, x.privilege_type, x.is_grantable
                FROM aclexplode(COALESCE(d.datacl, acldefault('d', d.datdba))) x
            ), expected AS (
SELECT d.datdba AS grantor, d.datdba AS grantee,
                       p AS privilege_type, false AS is_grantable
                FROM unnest(ARRAY['CONNECT', 'CREATE', 'TEMPORARY']) p
                UNION ALL
                SELECT d.datdba, 0::oid, p, false
                FROM unnest(ARRAY['CONNECT', 'TEMPORARY']) p
            )
            SELECT 1 FROM (
                (SELECT * FROM actual EXCEPT ALL SELECT * FROM expected)
                UNION ALL
                (SELECT * FROM expected EXCEPT ALL SELECT * FROM actual)
            ) difference
        ) THEN
            RAISE EXCEPTION 'Initial ACL differs: missing, duplicate, extra, grantor or grant-option mismatch: %', d.datname;
        END IF;
        FOR r IN SELECT rolname FROM pg_roles WHERE rolname IN (
            'adc_dev_app', 'adc_test_app', 'adc_dev_migrator', 'adc_test_migrator'
        ) LOOP
            IF has_database_privilege(r.rolname, d.oid, 'CONNECT')
                   IS DISTINCT FROM true
               OR has_database_privilege(r.rolname, d.oid, 'CREATE')
               OR has_database_privilege(r.rolname, d.oid, 'TEMP') IS DISTINCT FROM true
               OR has_database_privilege(r.rolname, d.oid, 'CONNECT WITH GRANT OPTION')
               OR has_database_privilege(r.rolname, d.oid, 'CREATE WITH GRANT OPTION')
               OR has_database_privilege(r.rolname, d.oid, 'TEMPORARY WITH GRANT OPTION') THEN
                RAISE EXCEPTION 'Unexpected effective privileges: %, %', r.rolname, d.datname;
            END IF;
        END LOOP;
    END LOOP;
END;
$check$;
