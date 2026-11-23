-- Pending permission increment for V008. Not executed or silently merged into its approved hash.
-- Reuses the existing clinical course identity; no new role/table or UPDATE/DELETE privilege.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'adc_course_validation'
     OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
     OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
    RAISE EXCEPTION 'Wrong course target';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='adc_course_health'
      AND NOT rolsuper AND NOT rolcreaterole AND NOT rolcreatedb AND NOT rolbypassrls AND NOT rolinherit) THEN
    RAISE EXCEPTION 'Course clinical role prerequisites missing';
  END IF;
END $$;
GRANT SELECT, INSERT ON adc.treatment_consent_result TO adc_course_health;
COMMIT;
