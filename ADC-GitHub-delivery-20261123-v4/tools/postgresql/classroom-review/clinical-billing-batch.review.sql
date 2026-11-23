-- Explicit clinicalBillingBatch20261122 approval. Dedicated course target only.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='20s';
SELECT pg_advisory_xact_lock(41005,1);
DO $guard$
DECLARE item record; first_id bigint;
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
 OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc'::regnamespace)) THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
 FOR item IN SELECT * FROM (VALUES
 ('course_visit_id_seq','visit','visit_id','adc_course_booking_writer'),
 ('course_health_change_id_seq','patient_health_change','health_change_id','adc_course_health'),
 ('course_consent_id_seq','treatment_consent_result','consent_result_id','adc_course_health'),
 ('course_clinical_record_id_seq','clinical_record','clinical_record_id','adc_course_health'),
 ('course_invoice_id_seq','treatment_invoice','invoice_id','adc_course_billing'),
 ('course_invoice_item_id_seq','treatment_invoice_item','invoice_item_id','adc_course_billing'),
 ('course_payment_id_seq','payment_transaction','payment_id','adc_course_billing')) AS x(seq,tab,col,role_name)
 LOOP
  IF to_regclass('adc.'||item.seq) IS NOT NULL THEN RAISE EXCEPTION 'CLINICAL_SEQUENCE_EXISTS_STOP_NO_REPLAY'; END IF;
  EXECUTE format('LOCK TABLE adc.%I IN SHARE ROW EXCLUSIVE MODE',item.tab);
  EXECUTE format('SELECT greatest(coalesce(max(%I),0)+1,1000000) FROM adc.%I',item.col,item.tab) INTO first_id;
  EXECUTE format('CREATE SEQUENCE adc.%I AS bigint START WITH %s',item.seq,first_id);
  EXECUTE format('REVOKE ALL ON SEQUENCE adc.%I FROM PUBLIC',item.seq);
  EXECUTE format('GRANT USAGE ON SEQUENCE adc.%I TO %I',item.seq,item.role_name);
 END LOOP;
END $guard$;
GRANT USAGE ON SEQUENCE adc.course_uc_audit_id_seq TO adc_course_health,adc_course_billing;
GRANT SELECT(visit_id,appointment_id,clinic_id,actual_dentist_id,registered_at,arrival_verification,registered_by,arrived_at),
 INSERT(visit_id,appointment_id,clinic_id,actual_dentist_id,registered_at,arrival_verification,registered_by,arrived_at)
 ON adc.visit TO adc_course_booking_writer;
GRANT UPDATE(invoice_status) ON adc.treatment_invoice TO adc_course_billing;
GRANT UPDATE(confirmation_information,confirmed_at) ON adc.treatment_invoice_version TO adc_course_billing;
GRANT UPDATE(transaction_result,received_amount,received_at,receipt_basis,verification_result,external_transaction_reference,local_settlement_status)
 ON adc.payment_transaction TO adc_course_billing;
GRANT INSERT ON adc.clinical_record_version TO adc_course_billing;
-- User confirmed the three-column read supplement on 2026-11-22.
GRANT SELECT(appointment_id,patient_id,appointment_status) ON adc.appointment TO adc_course_billing;
GRANT SELECT(consent_result_id,visit_id,proposed_treatment,consent_result) ON adc.treatment_consent_result TO adc_course_billing;
COMMIT;
