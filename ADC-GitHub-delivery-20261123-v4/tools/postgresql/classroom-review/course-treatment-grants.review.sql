-- Candidate V008 increment, not executed. Separate permission approval required.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'adc_course_validation'
     OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
     OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
    RAISE EXCEPTION 'Wrong course target';
  END IF;
END $$;
-- Existing role already SELECTs both clinical tables and the consent source.
-- Existing append-only services do not need UPDATE or DELETE here.
GRANT INSERT ON adc.clinical_record, adc.clinical_record_version TO adc_course_health;
COMMIT;
