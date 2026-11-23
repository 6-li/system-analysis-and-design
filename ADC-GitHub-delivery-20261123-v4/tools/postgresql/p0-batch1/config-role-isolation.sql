-- Isolated fixture setup only. Run after the unmodified V001-V004 scripts.
CREATE ROLE p0_config_app LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE p0_config_fixture LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;
GRANT CONNECT ON DATABASE adc_p0_batch1_test TO p0_config_app, p0_config_fixture;
-- Block accidental connections to cluster maintenance/template databases as well.
REVOKE CONNECT ON DATABASE postgres, template1 FROM PUBLIC;
GRANT USAGE ON SCHEMA adc TO p0_config_app, p0_config_fixture;
GRANT SELECT ON adc.clinic, adc.dentist, adc.project_duration_version,
    adc.extension_operation_audit_event TO p0_config_app;
GRANT INSERT (project_id, duration_version_no, project_name, duration_minutes, publication_status)
    ON adc.project_duration_version TO p0_config_app;
GRANT UPDATE (project_name, duration_minutes, publication_status, published_at, published_by, publication_evidence)
    ON adc.project_duration_version TO p0_config_app;
GRANT INSERT ON adc.extension_operation_audit_event TO p0_config_app;
GRANT INSERT ON adc.clinic, adc.dentist, adc.reception_schedule_version,
    adc.patient, adc.appointment TO p0_config_fixture;
REVOKE INSERT ON adc.clinic, adc.dentist FROM p0_probe_app;
