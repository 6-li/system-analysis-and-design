-- Approved quarterlyUsability20261122. Dedicated course environment only.
BEGIN;
SET LOCAL lock_timeout='2s';SET LOCAL statement_timeout='20s';
SELECT pg_advisory_xact_lock(41017,1);
DO $guard$
DECLARE item record; first_id bigint;
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432 OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF EXISTS(SELECT 1 FROM pg_namespace WHERE nspname='adc_course_source') OR EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema='adc' AND table_name='clinical_record_version' AND column_name IN ('patient_findings','aftercare_instructions')) THEN RAISE EXCEPTION 'USABILITY_OBJECT_EXISTS_STOP'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc'::regnamespace)) THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
 FOR item IN SELECT * FROM (VALUES
 ('course_quarterly_statistic_id_seq','quarterly_operating_statistic','statistic_id'),
 ('course_quarterly_issue_id_seq','quarterly_data_issue','issue_id'),
 ('course_quarterly_correction_id_seq','quarterly_issue_correction_history','correction_history_id'),
 ('course_quarterly_calculation_id_seq','quarterly_operating_calculation_version','calculation_id'),
 ('course_financial_review_id_seq','financial_review','review_id')) AS x(seq,tab,col)
 LOOP
  IF to_regclass('adc.'||item.seq) IS NOT NULL THEN RAISE EXCEPTION 'QUARTER_SEQUENCE_EXISTS_STOP'; END IF;
  EXECUTE format('LOCK TABLE adc.%I IN SHARE ROW EXCLUSIVE MODE',item.tab);
  EXECUTE format('SELECT greatest(coalesce(max(%I),0)+1,1000000) FROM adc.%I',item.col,item.tab) INTO first_id;
  EXECUTE format('CREATE SEQUENCE adc.%I AS bigint START WITH %s',item.seq,first_id);
  EXECUTE format('REVOKE ALL ON SEQUENCE adc.%I FROM PUBLIC',item.seq);
  EXECUTE format('GRANT USAGE ON SEQUENCE adc.%I TO adc_course_quarterly',item.seq);
 END LOOP;
END $guard$;
GRANT USAGE ON SEQUENCE adc.course_uc_audit_id_seq TO adc_course_quarterly;
-- Explicit supplemental approval: link reviewed correction to new snapshot.
GRANT UPDATE(resulting_statistic_id) ON adc.quarterly_issue_correction_history TO adc_course_quarterly;
GRANT SELECT(appointment_id,schedule_id,schedule_version_no,operated_at,starts_at,appointment_status,no_show_verification,cancellation_verification) ON adc.appointment TO adc_course_quarterly;
GRANT SELECT(schedule_id,schedule_version_no,clinic_id) ON adc.reception_schedule_version TO adc_course_quarterly;
GRANT SELECT(visit_id,appointment_id,clinic_id,actual_dentist_id,arrival_verification) ON adc.visit TO adc_course_quarterly;
GRANT SELECT(clinical_record_id,visit_id) ON adc.clinical_record TO adc_course_quarterly;
GRANT SELECT(clinical_record_id,record_version_no,responsible_dentist_id,implementation_result) ON adc.clinical_record_version TO adc_course_quarterly;
GRANT SELECT(invoice_id,visit_id) ON adc.treatment_invoice TO adc_course_quarterly;
GRANT SELECT(invoice_id,invoice_version_no,is_current,confirmation_information,confirmed_at,total_amount) ON adc.treatment_invoice_version TO adc_course_quarterly;
GRANT SELECT(invoice_item_id,invoice_id,invoice_version_no,clinical_record_id,record_version_no,line_amount) ON adc.treatment_invoice_item TO adc_course_quarterly;
GRANT SELECT(payment_id,invoice_id,invoice_version_no,local_settlement_status,transaction_result,received_at,received_amount) ON adc.payment_transaction TO adc_course_quarterly;
GRANT SELECT(consumption_id,stock_id,occurred_at,consumption_cost) ON adc.supply_consumption TO adc_course_quarterly;
GRANT SELECT(stock_id,clinic_id) ON adc.medical_supply_stock TO adc_course_quarterly;
ALTER TABLE adc.clinical_record_version ADD COLUMN patient_findings TEXT, ADD COLUMN aftercare_instructions TEXT;
GRANT SELECT(patient_findings,aftercare_instructions) ON adc.clinical_record_version TO adc_course_mini_read;
CREATE SCHEMA adc_course_source;
REVOKE ALL ON SCHEMA adc_course_source FROM PUBLIC;
CREATE TABLE adc_course_source.consumption_cost_fact(
 fact_id BIGINT PRIMARY KEY,
 clinic_id BIGINT NOT NULL REFERENCES adc.clinic(clinic_id),
 supply_id BIGINT NOT NULL REFERENCES adc.medical_supply(supply_id),
 quantity NUMERIC(18,3) NOT NULL CHECK(quantity>0),occurred_at TIMESTAMPTZ(6) NOT NULL,
 unit_cost NUMERIC(18,2) NOT NULL CHECK(unit_cost>=0),verified_cost NUMERIC(18,2) NOT NULL CHECK(verified_cost>=0),
 verification_basis TEXT NOT NULL CHECK(btrim(verification_basis)<>''),verified_by VARCHAR(100) NOT NULL,verified_at TIMESTAMPTZ(6) NOT NULL,
 request_key UUID NOT NULL,source_reference VARCHAR(200) NOT NULL UNIQUE,UNIQUE(clinic_id,request_key)
);
REVOKE ALL ON adc_course_source.consumption_cost_fact FROM PUBLIC;
GRANT USAGE ON SCHEMA adc_course_source TO adc_course_inventory;
GRANT SELECT,INSERT ON adc_course_source.consumption_cost_fact TO adc_course_inventory;
COMMIT;
