-- Approved bookingBatch20261122, dedicated course target only.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='15s';
SELECT pg_advisory_xact_lock(41004,1);
DO $$ BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
 OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF to_regclass('adc_mini.request_decision') IS NOT NULL THEN RAISE EXCEPTION 'DECISION_TABLE_EXISTS_STOP_NO_REPLAY'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc_mini'::regnamespace)) THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
END $$;
CREATE TABLE adc_mini.request_decision (
 decision_id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE REFERENCES adc_mini.preliminary_request(request_id),
 expected_request_version integer NOT NULL CHECK(expected_request_version>=0),
 decision_status text NOT NULL CHECK(decision_status='REJECTED'),
 reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 2000),
 decided_by text NOT NULL CHECK(length(btrim(decided_by))>0),
 decided_at timestamptz(6) NOT NULL,
 idempotency_key uuid NOT NULL,
 request_hash text NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 UNIQUE(decided_by,idempotency_key)
);
REVOKE ALL ON adc_mini.request_decision FROM PUBLIC;
GRANT SELECT ON adc_mini.request_decision TO adc_course_booking_writer,adc_course_patient_intake,adc_course_mini_read;
GRANT INSERT ON adc_mini.request_decision TO adc_course_booking_writer;
COMMIT;
