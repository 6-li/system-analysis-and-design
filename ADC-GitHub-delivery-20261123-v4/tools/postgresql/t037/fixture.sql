-- Disposable T037 instance only. Appointment and schedule rows come from the T028 fixture.
GRANT SELECT, INSERT ON adc.appointment_resolution TO p0_t028_runtime;
GRANT UPDATE (resolution_id) ON adc.appointment_resolution TO p0_t028_runtime;
GRANT UPDATE (appointment_id) ON adc.appointment TO p0_t028_runtime;
GRANT UPDATE (activation_status, activated_at) ON adc.reception_schedule_version TO p0_t028_runtime;
GRANT INSERT ON adc.reception_schedule_version TO p0_t028_runtime;
CREATE SEQUENCE adc.uc21_schedule_id_alloc AS bigint START 88000 NO CYCLE;
GRANT USAGE ON SEQUENCE adc.uc21_schedule_id_alloc TO p0_t028_runtime;
GRANT INSERT ON adc.appointment_change_history TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.patient TO p0_t028_runtime;
GRANT UPDATE (patient_name,identity_information,contact_information,emergency_contact_information,
 representative_identity_information,representative_verification_information) ON adc.patient TO p0_t028_runtime;
INSERT INTO adc.reception_schedule_version
  (schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,activation_status,
   qualification_check_result,conflict_check_result)
VALUES (87001,3,81001,79001,'2026-11-08 09:00Z','2026-11-08 11:00Z','DRAFT','SYNTHETIC','SYNTHETIC'),
       (87002,2,81002,79001,'2026-11-08 12:00Z','2026-11-08 16:00Z','DRAFT','SYNTHETIC','SYNTHETIC');
