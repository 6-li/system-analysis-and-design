DO $check$
DECLARE
    role_names name[] := ARRAY[
        'adc_dev_app', 'adc_test_app',
        'adc_dev_migrator', 'adc_test_migrator'
    ]::name[];
BEGIN
    IF (SELECT count(*) FROM pg_roles WHERE rolname = ANY(role_names)) <> 4 THEN
        RAISE EXCEPTION 'Exactly four reviewed ADC roles are required';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_roles WHERE rolname = ANY(role_names)
          AND (rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole
               OR rolinherit OR rolreplication OR rolbypassrls
               OR rolconnlimit <> -1 OR rolvaliduntil IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'ADC roles must remain NOLOGIN with fresh, unprivileged attributes';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_auth_members m
        WHERE m.roleid IN (SELECT oid FROM pg_roles WHERE rolname = ANY(role_names))
           OR m.member IN (SELECT oid FROM pg_roles WHERE rolname = ANY(role_names))
    ) THEN
        RAISE EXCEPTION 'ADC roles must not grant or receive role memberships, including SET ROLE paths';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_db_role_setting
        WHERE setrole IN (SELECT oid FROM pg_roles WHERE rolname = ANY(role_names))
    ) THEN
        RAISE EXCEPTION 'Unexpected role-specific settings; no setting values will be printed';
    END IF;
    IF EXISTS (
        SELECT 1 FROM pg_database
        WHERE datdba IN (SELECT oid FROM pg_roles WHERE rolname = ANY(role_names))
    ) THEN
        RAISE EXCEPTION 'ADC project roles must not own any database';
    END IF;
END;
$check$;
