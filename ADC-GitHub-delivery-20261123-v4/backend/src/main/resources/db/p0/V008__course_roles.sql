-- New empty course database only. No passwords in migration files.
-- Distinct LOGIN roles, no memberships, no PUBLIC database/schema access.
DO $$
DECLARE role_name text;
BEGIN
  IF current_database() <> 'adc_course_validation' OR host(inet_server_addr()) <> '127.0.0.1'
     OR inet_server_port() = 5432 THEN RAISE EXCEPTION 'Wrong course target'; END IF;
  EXECUTE format('REVOKE CONNECT, TEMP ON DATABASE %I FROM PUBLIC', current_database());
  FOR role_name IN SELECT unnest(ARRAY['adc_course_read','adc_course_booking_writer','adc_course_patient_intake',
    'adc_course_mini_read','adc_course_health','adc_course_schedule','adc_course_schedule_read',
    'adc_course_billing','adc_course_inventory','adc_course_quarterly','p0_config_app']) LOOP
    EXECUTE format('CREATE ROLE %I LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS', role_name);
    EXECUTE format('GRANT CONNECT ON DATABASE %I TO %I',current_database(),role_name);
    EXECUTE format('GRANT USAGE ON SCHEMA adc TO %I',role_name);
  END LOOP;
END $$;
REVOKE ALL ON SCHEMA adc FROM PUBLIC;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT SELECT ON adc.clinic, adc.dentist TO p0_config_app;
GRANT SELECT ON adc.appointment, adc.reception_schedule_version TO adc_course_read;
GRANT SELECT ON adc.clinic, adc.dentist, adc.reception_schedule_version, adc.appointment, adc.project_duration_version,
  adc.operation_audit_event TO adc_course_booking_writer;
GRANT SELECT (appointment_id) ON adc.visit TO adc_course_booking_writer;
GRANT INSERT ON adc.appointment, adc.operation_audit_event TO adc_course_booking_writer;
GRANT UPDATE(dentist_id) ON adc.dentist TO adc_course_booking_writer;
GRANT UPDATE(schedule_id) ON adc.reception_schedule_version TO adc_course_booking_writer;
GRANT SELECT, INSERT ON adc.patient, adc.operation_audit_event TO adc_course_patient_intake;
GRANT SELECT ON adc.appointment, adc.reception_schedule_version, adc.operation_audit_event,
  adc.appointment_change_history TO adc_course_mini_read;
GRANT SELECT(visit_id,appointment_id,clinic_id,registered_at) ON adc.visit TO adc_course_mini_read;
GRANT SELECT(clinical_record_id,visit_id) ON adc.clinical_record TO adc_course_mini_read;
GRANT SELECT(clinical_record_id,record_version_no,performed_treatment,implementation_result,is_current)
  ON adc.clinical_record_version TO adc_course_mini_read;
GRANT SELECT ON adc.patient_health_change, adc.clinical_record, adc.clinical_record_version,
  adc.visit, adc.appointment, adc.operation_audit_event TO adc_course_health;
GRANT INSERT ON adc.patient_health_change, adc.operation_audit_event TO adc_course_health;
GRANT SELECT ON adc.reception_schedule_version, adc.appointment TO adc_course_schedule_read;
-- No direct dentist UPDATE granted to scheduling/configuration readers.
-- Narrow lock functions and ID allocators retain their separate installation gate.
GRANT SELECT ON adc.clinic, adc.dentist, adc.reception_schedule_version, adc.appointment,
  adc.appointment_resolution, adc.schedule_maintenance_issue, adc.operation_audit_event,
  adc.appointment_change_history, adc.visit TO adc_course_schedule;
GRANT INSERT ON adc.reception_schedule_version, adc.appointment_resolution,
  adc.schedule_maintenance_issue, adc.operation_audit_event TO adc_course_schedule;
GRANT SELECT ON adc.treatment_invoice, adc.treatment_invoice_version, adc.treatment_invoice_item,
  adc.payment_transaction, adc.visit, adc.clinical_record, adc.clinical_record_version,
  adc.operation_audit_event TO adc_course_billing;
GRANT INSERT ON adc.treatment_invoice, adc.treatment_invoice_version, adc.treatment_invoice_item,
  adc.payment_transaction, adc.operation_audit_event TO adc_course_billing;
GRANT SELECT ON adc.medical_supply, adc.medical_supply_stock TO adc_course_inventory;
GRANT SELECT ON adc.operation_audit_event TO adc_course_inventory, adc_course_quarterly;
GRANT INSERT ON adc.operation_audit_event TO adc_course_inventory, adc_course_quarterly;
-- User-approved UC08 deployment increment, 2026-11-19. No UPDATE/DELETE.
GRANT SELECT, INSERT ON adc.treatment_consent_result TO adc_course_health;
-- User-approved UC09 deployment increment, 2026-11-19. Append only.
GRANT INSERT ON adc.clinical_record, adc.clinical_record_version TO adc_course_health;

GRANT UPDATE (billing_question, handling_basis, is_current)
    ON adc.treatment_invoice_version TO adc_course_billing;
GRANT UPDATE (is_current)
    ON adc.clinical_record_version TO adc_course_billing;
GRANT SELECT (clinic_id) ON adc.clinic TO adc_course_inventory;
GRANT SELECT, INSERT ON adc.purchase_requirement TO adc_course_inventory;
GRANT UPDATE (procurement_status, unavailable_supply_explanation)
    ON adc.purchase_requirement TO adc_course_inventory;
GRANT SELECT, INSERT ON adc.supply_receipt TO adc_course_inventory;
GRANT UPDATE (stock_id, delivery_reference, acceptance_result, delivery_discrepancy,
    discrepancy_status, received_quantity, purchase_cost, expiry_date, registered_by, registered_at)
    ON adc.supply_receipt TO adc_course_inventory;
GRANT UPDATE (on_hand_quantity) ON adc.medical_supply_stock TO adc_course_inventory;
GRANT SELECT, INSERT ON adc.supply_consumption TO adc_course_inventory;
GRANT UPDATE (on_hand_quantity) ON adc.medical_supply_stock TO adc_course_inventory;
GRANT SELECT, INSERT ON adc.quarterly_operating_statistic, adc.quarterly_statistic_clinic
    TO adc_course_quarterly;
GRANT INSERT ON adc.quarterly_dentist_performance_stat, adc.quarterly_data_issue
    TO adc_course_quarterly;
GRANT SELECT ON adc.quarterly_data_issue, adc.quarterly_issue_correction_history TO adc_course_quarterly;
GRANT UPDATE (corrected_value, correction_basis, business_attribution, corrected_by,
    correction_status, consistency_check_result) ON adc.quarterly_data_issue TO adc_course_quarterly;
GRANT INSERT ON adc.quarterly_issue_correction_history TO adc_course_quarterly;
GRANT UPDATE (consistency_check_result, check_basis, checked_at, correction_status)
    ON adc.quarterly_issue_correction_history TO adc_course_quarterly;
GRANT SELECT ON adc.quarterly_dentist_performance_stat TO adc_course_quarterly;
GRANT SELECT, INSERT ON adc.quarterly_operating_calculation_version TO adc_course_quarterly;
GRANT SELECT, INSERT ON adc.quarterly_dentist_performance_calc TO adc_course_quarterly;
GRANT SELECT, INSERT ON adc.financial_review TO adc_course_quarterly;
