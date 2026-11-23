-- Additional permissions only for the disposable T029 validation role.
GRANT UPDATE (schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,no_show_verification,reminder_arrangement,cancellation_verification,operated_by,operated_at,
 representative_verification_basis,arrival_pending_notes) ON adc.appointment TO p0_t028_runtime;
GRANT SELECT,INSERT ON adc.appointment_change_history TO p0_t028_runtime;
