-- Review candidate, isolated course database only. Credentials are provisioned separately.
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
CREATE ROLE adc_course_mini_read LOGIN NOINHERIT NOSUPERUSER
    NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE adc_course_validation TO adc_course_mini_read;
GRANT USAGE ON SCHEMA adc TO adc_course_mini_read;
GRANT SELECT ON adc.appointment, adc.reception_schedule_version, adc.operation_audit_event,
    adc.appointment_change_history TO adc_course_mini_read;
GRANT SELECT (visit_id, appointment_id, clinic_id, registered_at) ON adc.visit TO adc_course_mini_read;
GRANT SELECT (clinical_record_id, visit_id) ON adc.clinical_record TO adc_course_mini_read;
GRANT SELECT (clinical_record_id, record_version_no, performed_treatment, implementation_result, is_current)
    ON adc.clinical_record_version TO adc_course_mini_read;
ROLLBACK;
