-- Review candidate for the next isolated end-to-end course run.
-- Existing course writer and adc_mini intake schema must already be present.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'adc_course_validation'
       OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
       OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
        RAISE EXCEPTION 'Wrong course target';
    END IF;
END
$$;
GRANT USAGE ON SCHEMA adc_mini TO adc_course_booking_writer;
GRANT SELECT (request_id, request_version, associated_patient_id, applicant_account, submitted_content)
    ON adc_mini.preliminary_request TO adc_course_booking_writer;
-- Required by the reused source adapter's SELECT FOR UPDATE.
GRANT UPDATE (request_version) ON adc_mini.preliminary_request TO adc_course_booking_writer;
GRANT SELECT (request_id, operation, event_id, original_request_reference),
      INSERT (request_id, operation, event_at, event_id, original_request_reference)
    ON adc_mini.operation_audit_event TO adc_course_booking_writer;
ROLLBACK;
