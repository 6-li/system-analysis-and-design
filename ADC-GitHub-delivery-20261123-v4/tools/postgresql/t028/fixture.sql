-- Dedicated test roles and fixture only. No production grants or allocators.
CREATE ROLE p0_t028_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;
CREATE ROLE p0_t028_publisher LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE;
REVOKE CONNECT, TEMP ON DATABASE adc_p0_uc02_validation FROM PUBLIC;
GRANT CONNECT ON DATABASE adc_p0_uc02_validation TO p0_t028_runtime,p0_t028_publisher;
GRANT USAGE ON SCHEMA adc TO p0_t028_runtime,p0_t028_publisher;
GRANT SELECT ON adc.reception_schedule_version,adc.dentist,adc.appointment,adc.visit,adc.project_duration_version,adc.operation_audit_event TO p0_t028_runtime;
GRANT UPDATE (schedule_id) ON adc.reception_schedule_version TO p0_t028_runtime;
GRANT UPDATE (dentist_id) ON adc.dentist TO p0_t028_runtime;
GRANT INSERT ON adc.appointment,adc.operation_audit_event TO p0_t028_runtime;
GRANT SELECT ON adc.project_duration_version TO p0_t028_publisher;
GRANT UPDATE (publication_status,published_at,published_by,publication_evidence) ON adc.project_duration_version TO p0_t028_publisher;

INSERT INTO adc.clinic(clinic_id,clinic_name) VALUES (81001,'SYNTHETIC A'),(81002,'SYNTHETIC B');
INSERT INTO adc.dentist(dentist_id,practice_qualification) VALUES (79001,'SYNTHETIC qualification');
INSERT INTO adc.patient(patient_id,patient_name,identity_information,contact_information)
VALUES (88001,'SYNTHETIC A','SYNTHETIC ID A','a@example.invalid'),(88002,'SYNTHETIC B','SYNTHETIC ID B','b@example.invalid');
INSERT INTO adc.reception_schedule_version
(schedule_id,schedule_version_no,clinic_id,dentist_id,starts_at,ends_at,activation_status,
 qualification_check_result,conflict_check_result,activated_at)
VALUES
(87001,1,81001,79001,'2026-11-08 09:00Z','2026-11-08 12:00Z','SUPERSEDED','SYNTHETIC','SYNTHETIC','2026-11-01 00:00Z'),
(87001,2,81001,79001,'2026-11-08 09:00Z','2026-11-08 12:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-02 00:00Z'),
(87002,1,81002,79001,'2026-11-08 12:00Z','2026-11-08 16:00Z','EFFECTIVE','SYNTHETIC','SYNTHETIC','2026-11-02 00:00Z');
INSERT INTO adc.appointment
(appointment_id,patient_id,schedule_id,schedule_version_no,starts_at,ends_at,treatment_request,manual_confirmation,
 appointment_status,reminder_arrangement,operated_by,operated_at)
VALUES
(901,88001,87001,1,'2026-11-08 09:00Z','2026-11-08 09:27Z','SYNTHETIC legacy','SYNTHETIC acceptance','待到诊','SYNTHETIC reminder','SYNTHETIC staff','2026-11-02 00:00Z'),
(902,88002,87002,1,'2026-11-08 12:00Z','2026-11-08 12:27Z','SYNTHETIC legacy','SYNTHETIC acceptance','待到诊','SYNTHETIC reminder','SYNTHETIC staff','2026-11-02 00:00Z');
INSERT INTO adc.project_duration_version
(project_id,duration_version_no,project_name,duration_minutes,publication_status,published_at,published_by,publication_evidence)
SELECT id,1,'SYNTHETIC project',27,'CURRENT','2026-11-01 00:00Z','SYNTHETIC publisher',
 jsonb_build_object('schemaVersion',1,'requestReference','SYNTHETIC/v1/'||id,'canonicalPayloadHash',repeat('a',64))
FROM unnest(ARRAY[85001,85002,85003]) AS ids(id);
INSERT INTO adc.project_duration_version(project_id,duration_version_no,project_name,duration_minutes,publication_status)
SELECT id,2,'SYNTHETIC project',27,'DRAFT' FROM unnest(ARRAY[85002,85003]) AS ids(id);
