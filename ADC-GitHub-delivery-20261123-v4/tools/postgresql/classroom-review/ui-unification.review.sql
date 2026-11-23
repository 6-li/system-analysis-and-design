-- Approved uiUnification20261122; additive only, no legacy fact migration.
BEGIN;
SET LOCAL lock_timeout='2s'; SET LOCAL statement_timeout='20s';
SELECT pg_advisory_xact_lock(41023,1);
DO $$ BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432 OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF to_regclass('adc_course_source.consent_project_scope') IS NOT NULL OR EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='adc' AND table_name='clinical_record_version' AND column_name='performed_project_key') THEN RAISE EXCEPTION 'UI_DEPENDENCY_EXISTS_STOP'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc'::regnamespace,'adc_course_source'::regnamespace)) THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
END $$;
GRANT SELECT(visit_id,appointment_id,clinic_id,actual_dentist_id,registered_at) ON adc.visit TO adc_course_read;
CREATE TABLE adc_course_source.consent_project_scope(
 consent_result_id BIGINT NOT NULL REFERENCES adc.treatment_consent_result(consent_result_id),
 project_key VARCHAR(80) NOT NULL,catalog_version VARCHAR(200) NOT NULL,
 project_label_snapshot VARCHAR(200) NOT NULL,unit_snapshot VARCHAR(40) NOT NULL,
 PRIMARY KEY(consent_result_id,project_key)
);
REVOKE ALL ON adc_course_source.consent_project_scope FROM PUBLIC;
GRANT USAGE ON SCHEMA adc_course_source TO adc_course_health,adc_course_billing;
GRANT SELECT,INSERT ON adc_course_source.consent_project_scope TO adc_course_health;
GRANT SELECT ON adc_course_source.consent_project_scope TO adc_course_billing;
ALTER TABLE adc.clinical_record_version ADD COLUMN performed_project_key VARCHAR(80);
COMMIT;
