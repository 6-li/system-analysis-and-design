-- Approved course UC03 increment only. Never replay historical migrations.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
SELECT pg_advisory_xact_lock(41003,1);
DO $guard$
DECLARE first_id bigint;
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
   OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet
 THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF to_regclass('adc.course_change_id_seq') IS NOT NULL THEN RAISE EXCEPTION 'CHANGE_SEQUENCE_EXISTS_STOP_NO_REPLAY'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='adc_course_booking_writer' AND NOT rolsuper AND NOT rolcreaterole)
 THEN RAISE EXCEPTION 'COURSE_WRITER_REQUIRED'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc'::regnamespace))
 THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
 LOCK TABLE adc.appointment_change_history IN SHARE ROW EXCLUSIVE MODE;
 SELECT greatest(coalesce(max(appointment_change_id),0)+1,1000000) INTO first_id FROM adc.appointment_change_history;
 EXECUTE format('CREATE SEQUENCE adc.course_change_id_seq AS bigint START WITH %s',first_id);
END $guard$;
GRANT USAGE ON SEQUENCE adc.course_change_id_seq TO adc_course_booking_writer;
GRANT SELECT,INSERT ON adc.appointment_change_history TO adc_course_booking_writer;
GRANT UPDATE(schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,no_show_verification,reminder_arrangement,cancellation_verification,operated_by,
 operated_at,representative_verification_basis,arrival_pending_notes) ON adc.appointment TO adc_course_booking_writer;
COMMIT;
