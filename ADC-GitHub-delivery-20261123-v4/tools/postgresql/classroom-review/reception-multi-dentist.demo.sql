-- Explicit SYNTHETIC data for a NEW course instance, not a migration or real clinic directory.
-- Original columns only. Different dentists may have concurrent windows.
INSERT INTO adc.clinic(clinic_id,clinic_name) VALUES(81001,'SYNTHETIC 演示诊所一'),(81002,'SYNTHETIC 演示诊所二');
INSERT INTO adc.dentist(dentist_id,practice_qualification) VALUES
 (79001,'SYNTHETIC 演示牙医甲资质'),(79002,'SYNTHETIC 演示牙医乙资质'),(79003,'SYNTHETIC 演示牙医丙资质');
INSERT INTO adc.patient(patient_id,patient_name,identity_information,contact_information) VALUES
 (88001,'SYNTHETIC 患者甲','SYNTHETIC ID A','a@example.invalid'),(88002,'SYNTHETIC 患者乙','SYNTHETIC ID B','b@example.invalid');
INSERT INTO adc.reception_schedule_version(schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,
 activation_status,qualification_check_result,conflict_check_result,activated_at) VALUES
 (87001,1,81001,79001,'2026-11-20 09:00Z','2026-11-20 12:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-19 00:00Z'),
 (87002,1,81001,79002,'2026-11-20 09:00Z','2026-11-20 12:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-19 00:00Z'),
 (87003,1,81001,79003,'2026-11-20 13:00Z','2026-11-20 16:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-19 00:00Z'),
 (87004,1,81002,79001,'2026-11-20 13:00Z','2026-11-20 16:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-19 00:00Z');
INSERT INTO adc.appointment(appointment_id,patient_id,schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,
 manual_confirmation,appointment_status,reminder_arrangement,operated_by,operated_at) VALUES
 (901,88001,87001,1,'2026-11-20 09:00Z','2026-11-20 09:27Z','SYNTHETIC treatment','SYNTHETIC accepted','待到诊','SYNTHETIC reminder','SYNTHETIC reception','2026-11-19 00:00Z'),
 (902,88002,87004,1,'2026-11-20 13:00Z','2026-11-20 13:27Z','SYNTHETIC treatment','SYNTHETIC accepted','待到诊','SYNTHETIC reminder','SYNTHETIC reception','2026-11-19 00:00Z');
