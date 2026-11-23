-- Permissions and fixture limited to the disposable attendance-validation cluster.
GRANT INSERT ON adc.visit TO p0_t028_runtime;
INSERT INTO adc.appointment
(appointment_id,patient_id,schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,reminder_arrangement,operated_by,operated_at)
VALUES (904,88001,87001,2,'2026-11-08 10:00Z','2026-11-08 10:27Z','SYNTHETIC legacy','SYNTHETIC acceptance',
 '待到诊','SYNTHETIC reminder','SYNTHETIC staff','2026-11-02 00:00Z');
