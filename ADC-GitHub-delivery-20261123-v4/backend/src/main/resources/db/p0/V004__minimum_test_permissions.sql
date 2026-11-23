-- Only roles in the newly created isolated cluster; not a grant for existing databases.
GRANT USAGE ON SCHEMA adc TO p0_probe_app;
GRANT SELECT, INSERT ON ALL TABLES IN SCHEMA adc TO p0_probe_app;
GRANT UPDATE ON adc.reception_schedule_version, adc.appointment, adc.project_duration_version TO p0_probe_app;
-- PostgreSQL requires UPDATE privilege for SELECT ... FOR UPDATE.
GRANT UPDATE(dentist_id) ON adc.dentist TO p0_probe_app;
GRANT UPDATE(resolution_id) ON adc.appointment_resolution TO p0_probe_app;
-- These two key-column privileges serve FOR UPDATE only. No key mutation API exists.
-- No full-table UPDATE on table28, no UPDATE on either audit or table51; no DELETE.
