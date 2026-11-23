-- Authorized synthetic clinic directory only. No clinical or financial facts.
BEGIN;
SET LOCAL lock_timeout = '5s';
DO $guard$
BEGIN
 IF current_database() <> 'adc_course_validation' OR current_setting('port') <> '55432'
 OR current_setting('server_version_num')::int / 10000 <> 18 THEN
  RAISE EXCEPTION 'TARGET_MISMATCH';
 END IF;
 IF NOT EXISTS (SELECT 1 FROM adc.clinic WHERE clinic_id=81001) THEN
  RAISE EXCEPTION 'BASE_CLINIC_REQUIRED';
 END IF;
 IF EXISTS (SELECT 1 FROM adc.clinic WHERE clinic_id=81002 AND clinic_name <> 'ADC中心门诊（课程合成）') THEN
  RAISE EXCEPTION 'CLINIC_81002_CONFLICT_NO_OVERWRITE';
 END IF;
END $guard$;
INSERT INTO adc.clinic (clinic_id,clinic_name)
VALUES (81002,'ADC中心门诊（课程合成）') ON CONFLICT (clinic_id) DO NOTHING;
DO $guard$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM adc.clinic WHERE clinic_id=81002 AND clinic_name='ADC中心门诊（课程合成）') THEN
  RAISE EXCEPTION 'CLINIC_81002_UNVERIFIED';
 END IF;
END $guard$;
COMMIT;
