-- Candidate only. NOT executed. Separate approval required for this object and grants.
-- Only a NEW, empty, independently verified local course database; never an upgrade.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'adc_course_validation'
     OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
     OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
    RAISE EXCEPTION 'Wrong course target';
  END IF;
  IF EXISTS (SELECT 1 FROM adc.operation_audit_event)
     OR EXISTS (SELECT 1 FROM adc.patient_health_change) THEN
    RAISE EXCEPTION 'Nonempty audit/health tables: stop; no automatic calibration';
  END IF;
  IF to_regclass('adc.course_uc_audit_id_seq') IS NOT NULL THEN
    RAISE EXCEPTION 'Existing allocator: stop; verify ownership, watermarks and all writers separately';
  END IF;
END $$;
CREATE SEQUENCE adc.course_uc_audit_id_seq AS bigint
  START WITH 100000 MINVALUE 1 MAXVALUE 9223372036854775807 NO CYCLE;
GRANT USAGE ON SEQUENCE adc.course_uc_audit_id_seq TO adc_course_health;
-- UC07 health IDs and UC audit IDs each consume a separate value from this sequence.
-- Sequence gaps after rollback are expected; never rewind, recycle, or infer success from an allocated ID.
-- Other writers receive no additional rights from this candidate.
COMMIT;
