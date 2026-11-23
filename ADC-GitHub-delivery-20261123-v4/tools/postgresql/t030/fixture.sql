-- Permissions and extra synthetic appointment for the disposable T030 instance only.
GRANT UPDATE (schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,no_show_verification,reminder_arrangement,cancellation_verification,operated_by,operated_at,
 representative_verification_basis,arrival_pending_notes) ON adc.appointment TO p0_t028_runtime;
GRANT SELECT ON adc.appointment_change_history TO p0_t028_runtime;

INSERT INTO adc.appointment
(appointment_id,patient_id,schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,reminder_arrangement,operated_by,operated_at)
VALUES (903,88001,87001,2,'2026-11-08 11:00Z','2026-11-08 11:27Z','SYNTHETIC legacy','SYNTHETIC acceptance',
 '待到诊','SYNTHETIC reminder','SYNTHETIC staff','2026-11-02 00:00Z');
