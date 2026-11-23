-- Only for the disposable T034 instance and test role.
GRANT INSERT ON adc.appointment_change_history,adc.visit TO p0_t028_runtime;

-- CT-SCH-03 and CT-SCH-13: overlapping EFFECTIVE windows for one dentist
-- cannot be created under a different schedule identity or clinic.
DO $$
BEGIN
  BEGIN
    INSERT INTO adc.reception_schedule_version
    (schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,activation_status,
     qualification_check_result,conflict_check_result,activated_at)
    VALUES (87099,1,81002,79001,'2026-11-08 19:00:00+08','2026-11-08 20:00:00+08','EFFECTIVE',
      'SYNTHETIC','SYNTHETIC','2026-11-02 00:00Z');
    RAISE EXCEPTION 'overlapping effective schedule was accepted';
  EXCEPTION WHEN exclusion_violation THEN
    RAISE NOTICE 'T034_CROSS_CLINIC_EFFECTIVE_OVERLAP_REJECTED';
  END;
END $$;
