-- SYNTHETIC additions for a NEW classroom instance containing clinic81001 and dentist79001.
INSERT INTO adc.dentist(dentist_id,practice_qualification) VALUES
 (79002,'SYNTHETIC 演示牙医乙资质'),(79003,'SYNTHETIC 演示牙医丙资质');
INSERT INTO adc.reception_schedule_version(schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,
 activation_status,qualification_check_result,conflict_check_result,activated_at) VALUES
 (87011,1,81001,79002,'2026-11-08 09:00Z','2026-11-08 12:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-01 00:00Z'),
 (87012,1,81001,79003,'2026-11-08 13:00Z','2026-11-08 16:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-01 00:00Z');
