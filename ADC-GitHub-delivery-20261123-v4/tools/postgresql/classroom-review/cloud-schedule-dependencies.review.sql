-- REVIEW CANDIDATE ONLY. Requires explicit approval before any database execution.
-- Stage: install UC21 dependencies, not UC22 activation or application enablement.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
SELECT pg_advisory_xact_lock(41002,1);
DO $guard$
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
    OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet
 THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF to_regnamespace('adc_uc21_lock') IS NOT NULL
    OR to_regclass('adc.uc21_schedule_id_alloc') IS NOT NULL
    OR to_regclass('adc.uc21_issue_id_alloc') IS NOT NULL
    OR to_regclass('adc.course_resolution_id_seq') IS NOT NULL
    OR EXISTS(SELECT 1 FROM pg_roles WHERE rolname='adc_course_schedule_lock_owner')
 THEN RAISE EXCEPTION 'SCHEDULE_OBJECTS_EXIST_STOP_NO_REPLAY'; END IF;
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname='adc_course_schedule' AND NOT rolsuper AND NOT rolcreaterole)
 THEN RAISE EXCEPTION 'EXPECTED_COURSE_SCHEDULE_ROLE_REQUIRED'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole
     AND defaclnamespace IN (0,'adc'::regnamespace))
 THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
END $guard$;
CREATE ROLE adc_course_schedule_lock_owner NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE SCHEMA adc_uc21_lock;
REVOKE ALL ON SCHEMA adc_uc21_lock FROM PUBLIC;
GRANT USAGE ON SCHEMA adc TO adc_course_schedule_lock_owner;
GRANT USAGE ON SCHEMA adc_uc21_lock TO adc_course_schedule_lock_owner,adc_course_schedule;
GRANT SELECT(dentist_id),UPDATE(dentist_id) ON adc.dentist TO adc_course_schedule_lock_owner;
GRANT SELECT(schedule_id,schedule_version_no),UPDATE(schedule_id)
 ON adc.reception_schedule_version TO adc_course_schedule_lock_owner;
CREATE FUNCTION adc_uc21_lock.lock_dentist(p_dentist_id pg_catalog.int8)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path TO pg_catalog,pg_temp
AS $f$
BEGIN
 PERFORM 1 FROM adc.dentist d WHERE d.dentist_id=p_dentist_id FOR UPDATE OF d;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='LOCK_RESOURCE_NOT_FOUND'; END IF;
END;
$f$;
CREATE FUNCTION adc_uc21_lock.lock_version(p_schedule_id pg_catalog.int8,p_version_no pg_catalog.int4)
RETURNS void LANGUAGE plpgsql VOLATILE SECURITY DEFINER
SET search_path TO pg_catalog,pg_temp
AS $f$
BEGIN
 PERFORM 1 FROM adc.reception_schedule_version s
 WHERE s.schedule_id=p_schedule_id AND s.schedule_version_no=p_version_no FOR UPDATE OF s;
 IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='P0002',MESSAGE='VERSION_NOT_FOUND'; END IF;
END;
$f$;
ALTER FUNCTION adc_uc21_lock.lock_dentist(bigint) OWNER TO adc_course_schedule_lock_owner;
ALTER FUNCTION adc_uc21_lock.lock_version(bigint,integer) OWNER TO adc_course_schedule_lock_owner;
REVOKE ALL ON FUNCTION adc_uc21_lock.lock_dentist(bigint),adc_uc21_lock.lock_version(bigint,integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION adc_uc21_lock.lock_dentist(bigint),adc_uc21_lock.lock_version(bigint,integer) TO adc_course_schedule;
DO $allocate$
DECLARE schedule_start bigint; issue_start bigint; resolution_start bigint;
BEGIN
 SELECT GREATEST(COALESCE(max(schedule_id),0)+1,1000000) INTO schedule_start FROM adc.reception_schedule_version;
 SELECT GREATEST(COALESCE(max(maintenance_issue_id),0)+1,1) INTO issue_start FROM adc.schedule_maintenance_issue;
 SELECT GREATEST(COALESCE(max(resolution_id),0)+1,1) INTO resolution_start FROM adc.appointment_resolution;
 EXECUTE format('CREATE SEQUENCE adc.uc21_schedule_id_alloc AS bigint START WITH %s NO CYCLE',schedule_start);
 EXECUTE format('CREATE SEQUENCE adc.uc21_issue_id_alloc AS bigint START WITH %s NO CYCLE',issue_start);
 EXECUTE format('CREATE SEQUENCE adc.course_resolution_id_seq AS bigint START WITH %s NO CYCLE',resolution_start);
END $allocate$;
REVOKE ALL ON SEQUENCE adc.uc21_schedule_id_alloc,adc.uc21_issue_id_alloc,adc.course_resolution_id_seq FROM PUBLIC;
GRANT USAGE ON SEQUENCE adc.uc21_schedule_id_alloc,adc.uc21_issue_id_alloc,adc.course_resolution_id_seq,
 adc.course_uc_audit_id_seq TO adc_course_schedule;
GRANT UPDATE(resulting_schedule_id,resulting_schedule_version_no)
 ON adc.schedule_maintenance_issue TO adc_course_schedule;
COMMIT;
