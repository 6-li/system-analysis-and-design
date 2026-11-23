-- Review only. Requires the isolated course mini-intake schema and shared audit sequence.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'adc_course_validation'
       OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
       OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
        RAISE EXCEPTION 'Wrong course target';
    END IF;
    IF EXISTS (SELECT 1 FROM adc.patient WHERE patient_id >= 100000) THEN
        RAISE EXCEPTION 'Synthetic patient ID range occupied';
    END IF;
END
$$;
CREATE ROLE adc_course_patient_intake LOGIN NOINHERIT NOSUPERUSER
    NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE SEQUENCE adc.course_patient_id_seq AS bigint START WITH 100000 MINVALUE 1 NO CYCLE;
GRANT CONNECT ON DATABASE adc_course_validation TO adc_course_patient_intake;
GRANT USAGE ON SCHEMA adc, adc_mini TO adc_course_patient_intake;
GRANT USAGE ON SEQUENCE adc.course_patient_id_seq, adc.course_uc_audit_id_seq TO adc_course_patient_intake;
GRANT SELECT, INSERT ON adc.patient, adc.operation_audit_event TO adc_course_patient_intake;
GRANT SELECT (request_id, applicant_account, submitted_content, request_version, associated_patient_id),
      UPDATE (request_version, associated_patient_id)
    ON adc_mini.preliminary_request TO adc_course_patient_intake;
GRANT SELECT (request_id, operation, event_id, original_request_reference),
      INSERT (request_id, operation, event_at, event_id, original_request_reference)
    ON adc_mini.operation_audit_event TO adc_course_patient_intake;
ROLLBACK;
