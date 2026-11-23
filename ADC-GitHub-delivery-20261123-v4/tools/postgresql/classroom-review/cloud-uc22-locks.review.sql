-- REVIEW ONLY. UC22-specific explicit approval required before execution.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
SELECT pg_advisory_xact_lock(41002,1);
DO $guard$
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
   OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet
 THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='adc_course_schedule_lock_owner' AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole)
    OR to_regprocedure('adc_uc21_lock.lock_dentist(bigint)') IS NULL
    OR to_regprocedure('adc_uc21_lock.lock_version(bigint,integer)') IS NULL
 THEN RAISE EXCEPTION 'APPROVED_BASE_LOCKS_REQUIRED'; END IF;
 IF to_regprocedure('adc_uc21_lock.lock_appointment(bigint)') IS NOT NULL
    OR to_regprocedure('adc_uc21_lock.lock_resolution(bigint)') IS NOT NULL
 THEN RAISE EXCEPTION 'UC22_LOCKS_EXIST_STOP_NO_REPLAY'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole
    AND defaclnamespace IN(0,'adc_uc21_lock'::regnamespace))
 THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
END $guard$;
GRANT SELECT(appointment_id),UPDATE(appointment_id) ON adc.appointment TO adc_course_schedule_lock_owner;
GRANT SELECT(resolution_id),UPDATE(resolution_id) ON adc.appointment_resolution TO adc_course_schedule_lock_owner;
CREATE FUNCTION adc_uc21_lock.lock_appointment(p_appointment_id pg_catalog.int8)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path TO pg_catalog,pg_temp
AS $f$
BEGIN
 PERFORM 1 FROM adc.appointment a WHERE a.appointment_id=p_appointment_id FOR UPDATE OF a;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='LOCK_RESOURCE_NOT_FOUND'; END IF;
END;
$f$;
CREATE FUNCTION adc_uc21_lock.lock_resolution(p_resolution_id pg_catalog.int8)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path TO pg_catalog,pg_temp
AS $f$
BEGIN
 PERFORM 1 FROM adc.appointment_resolution r WHERE r.resolution_id=p_resolution_id FOR UPDATE OF r;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='LOCK_RESOURCE_NOT_FOUND'; END IF;
END;
$f$;
ALTER FUNCTION adc_uc21_lock.lock_appointment(bigint) OWNER TO adc_course_schedule_lock_owner;
ALTER FUNCTION adc_uc21_lock.lock_resolution(bigint) OWNER TO adc_course_schedule_lock_owner;
REVOKE ALL ON FUNCTION adc_uc21_lock.lock_appointment(bigint),adc_uc21_lock.lock_resolution(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adc_uc21_lock.lock_appointment(bigint),adc_uc21_lock.lock_resolution(bigint) TO adc_course_schedule;
GRANT UPDATE(activation_status,activated_at) ON adc.reception_schedule_version TO adc_course_schedule;
COMMIT;
