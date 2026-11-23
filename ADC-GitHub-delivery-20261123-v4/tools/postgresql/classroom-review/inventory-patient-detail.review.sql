-- Approved inventoryPatientDetail20261122; no historical migration replay.
BEGIN;
SET LOCAL lock_timeout='2s';
SET LOCAL statement_timeout='20s';
SELECT pg_advisory_xact_lock(41013,1);
DO $guard$
DECLARE item record; first_id bigint;
BEGIN
 IF current_database()<>'adc_course_validation' OR current_setting('port')::int<>55432
 OR inet_server_addr() IS NOT NULL AND inet_server_addr()<>'127.0.0.1'::inet THEN RAISE EXCEPTION 'WRONG_COURSE_TARGET'; END IF;
 IF EXISTS(SELECT 1 FROM pg_default_acl WHERE defaclrole=current_user::regrole AND defaclnamespace IN(0,'adc'::regnamespace)) THEN RAISE EXCEPTION 'DEFAULT_ACL_REVIEW_REQUIRED'; END IF;
 LOCK TABLE adc.medical_supply,adc.medical_supply_stock IN SHARE ROW EXCLUSIVE MODE;
 IF EXISTS(SELECT 1 FROM adc.medical_supply WHERE supply_id=9000001 OR supply_name='SYNTHETIC 演示耗材')
 OR EXISTS(SELECT 1 FROM adc.medical_supply_stock WHERE stock_id=9000001 OR clinic_id=81001 AND supply_id=9000001) THEN RAISE EXCEPTION 'SYNTHETIC_BASE_EXISTS_STOP'; END IF;
 FOR item IN SELECT * FROM (VALUES
 ('course_purchase_requirement_id_seq','purchase_requirement','purchase_requirement_id'),
 ('course_supply_receipt_id_seq','supply_receipt','receipt_id'),
 ('course_supply_consumption_id_seq','supply_consumption','consumption_id')) AS x(seq,tab,col)
 LOOP
  IF to_regclass('adc.'||item.seq) IS NOT NULL THEN RAISE EXCEPTION 'INVENTORY_SEQUENCE_EXISTS_STOP'; END IF;
  EXECUTE format('LOCK TABLE adc.%I IN SHARE ROW EXCLUSIVE MODE',item.tab);
  EXECUTE format('SELECT greatest(coalesce(max(%I),0)+1,1000000) FROM adc.%I',item.col,item.tab) INTO first_id;
  EXECUTE format('CREATE SEQUENCE adc.%I AS bigint START WITH %s',item.seq,first_id);
  EXECUTE format('REVOKE ALL ON SEQUENCE adc.%I FROM PUBLIC',item.seq);
  EXECUTE format('GRANT USAGE ON SEQUENCE adc.%I TO adc_course_inventory',item.seq);
 END LOOP;
END $guard$;
GRANT USAGE ON SEQUENCE adc.course_uc_audit_id_seq TO adc_course_inventory;
GRANT SELECT(actual_dentist_id) ON adc.visit TO adc_course_mini_read;
GRANT SELECT(clinic_id,clinic_name) ON adc.clinic TO adc_course_mini_read;
GRANT SELECT(billing_unit,actual_quantity) ON adc.clinical_record_version TO adc_course_mini_read;
GRANT SELECT(invoice_id,visit_id,invoice_status) ON adc.treatment_invoice TO adc_course_mini_read;
GRANT SELECT(invoice_id,invoice_version_no,total_amount,is_current) ON adc.treatment_invoice_version TO adc_course_mini_read;
GRANT SELECT(invoice_item_id,invoice_id,invoice_version_no,billed_item,billing_unit,billed_quantity,unit_price,line_amount) ON adc.treatment_invoice_item TO adc_course_mini_read;
GRANT SELECT(payment_id,invoice_id,invoice_version_no,payment_method,requested_amount,received_amount,local_settlement_status,received_at) ON adc.payment_transaction TO adc_course_mini_read;
INSERT INTO adc.medical_supply(supply_id,supply_name,stock_unit) VALUES(9000001,'SYNTHETIC 演示耗材','件');
INSERT INTO adc.medical_supply_stock(stock_id,clinic_id,supply_id,on_hand_quantity,reorder_threshold,existing_warning)
 VALUES(9000001,81001,9000001,0,2,'SYNTHETIC 初始库存低于演示阈值');
COMMIT;
