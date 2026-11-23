-- Isolated review candidate: run only after independently checking the new local cluster.
-- The launcher must provision a generated SCRAM credential out of band.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'adc_course_validation'
       OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
       OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
        RAISE EXCEPTION 'Wrong course target';
    END IF;
    IF EXISTS (SELECT 1 FROM adc.appointment WHERE appointment_id >= 100000)
       OR EXISTS (SELECT 1 FROM adc.operation_audit_event WHERE audit_event_id >= 100000) THEN
        RAISE EXCEPTION 'Synthetic ID range occupied';
    END IF;
END
$$;
CREATE ROLE adc_course_booking_writer LOGIN NOINHERIT NOSUPERUSER
    NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE SEQUENCE adc.course_appointment_id_seq AS bigint START WITH 100000 MINVALUE 1 NO CYCLE;
CREATE SEQUENCE adc.course_uc_audit_id_seq AS bigint START WITH 100000 MINVALUE 1 NO CYCLE;
GRANT CONNECT ON DATABASE adc_course_validation TO adc_course_booking_writer;
GRANT USAGE ON SCHEMA adc TO adc_course_booking_writer;
GRANT USAGE ON SEQUENCE adc.course_appointment_id_seq, adc.course_uc_audit_id_seq TO adc_course_booking_writer;
GRANT SELECT ON adc.dentist, adc.reception_schedule_version, adc.appointment, adc.project_duration_version,
    adc.operation_audit_event TO adc_course_booking_writer;
GRANT INSERT ON adc.appointment, adc.operation_audit_event TO adc_course_booking_writer;
-- Required for existing L4/L5 SELECT FOR UPDATE. Neither grant is given to adc_course_read.
GRANT UPDATE (dentist_id) ON adc.dentist TO adc_course_booking_writer;
GRANT UPDATE (schedule_id) ON adc.reception_schedule_version TO adc_course_booking_writer;
ROLLBACK;
