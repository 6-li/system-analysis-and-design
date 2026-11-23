-- Approved reception inbox read scope; existing extension tables and course role required.
BEGIN;
DO $$
BEGIN
  IF current_database() <> 'adc_course_validation'
     OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
     OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
    RAISE EXCEPTION 'Wrong course target';
  END IF;
  IF to_regclass('adc_mini.preliminary_request') IS NULL OR to_regclass('adc_mini.operation_audit_event') IS NULL THEN
    RAISE EXCEPTION 'Mini source extension missing: stop, do not create fictitious source';
  END IF;
END $$;
GRANT USAGE ON SCHEMA adc_mini TO adc_course_patient_intake;
GRANT SELECT(request_id,request_version,received_at,associated_patient_id,submitted_content)
  ON adc_mini.preliminary_request TO adc_course_patient_intake;
GRANT SELECT(request_id,operation,event_id,original_request_reference)
  ON adc_mini.operation_audit_event TO adc_course_patient_intake;
GRANT SELECT(appointment_id,patient_id,schedule_id,schedule_version_no) ON adc.appointment TO adc_course_patient_intake;
GRANT SELECT(schedule_id,schedule_version_no,clinic_id) ON adc.reception_schedule_version TO adc_course_patient_intake;
GRANT SELECT(appointment_change_id,appointment_id,change_request_reference) ON adc.appointment_change_history TO adc_course_patient_intake;
GRANT SELECT(appointment_status,no_show_verification,cancellation_verification,reminder_arrangement) ON adc.appointment TO adc_course_patient_intake;
GRANT SELECT(appointment_id) ON adc.visit TO adc_course_patient_intake;
-- No INSERT/UPDATE/DELETE or new memberships granted by this add-on.
COMMIT;
