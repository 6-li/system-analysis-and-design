-- Tables 30, 32, 33 and 50 in the disposable classroom cluster.
CREATE TABLE adc.patient_health_change (
    health_change_id bigint PRIMARY KEY,
    visit_id bigint NOT NULL REFERENCES adc.visit(visit_id),
    medical_history text,
    allergy_information text,
    change_content text NOT NULL,
    verified_dentist_id bigint NOT NULL REFERENCES adc.dentist(dentist_id),
    changed_at timestamptz(6) NOT NULL
);

CREATE TABLE adc.treatment_consent_result (
    consent_result_id bigint PRIMARY KEY,
    visit_id bigint NOT NULL REFERENCES adc.visit(visit_id),
    communication_party text NOT NULL,
    guardian_participation text,
    proposed_treatment text NOT NULL,
    consent_result text NOT NULL,
    comments text,
    recorded_by varchar(100) NOT NULL,
    recorded_at timestamptz(6) NOT NULL,
    recording_dentist_id bigint NOT NULL REFERENCES adc.dentist(dentist_id)
);

CREATE TABLE adc.clinical_record (
    clinical_record_id bigint PRIMARY KEY,
    visit_id bigint NOT NULL REFERENCES adc.visit(visit_id)
);

CREATE TABLE adc.clinical_record_version (
    clinical_record_id bigint NOT NULL REFERENCES adc.clinical_record(clinical_record_id),
    record_version_no integer NOT NULL,
    consent_result_id bigint NOT NULL REFERENCES adc.treatment_consent_result(consent_result_id),
    responsible_dentist_id bigint NOT NULL REFERENCES adc.dentist(dentist_id),
    performed_treatment varchar(200),
    billing_unit varchar(40),
    actual_quantity decimal(18,3),
    implementation_result text NOT NULL,
    special_requirements text,
    non_performance_reason text,
    revision_basis text,
    recorded_by varchar(100) NOT NULL,
    recorded_at timestamptz(6) NOT NULL,
    is_current boolean NOT NULL,
    PRIMARY KEY (clinical_record_id, record_version_no)
);
CREATE UNIQUE INDEX clinical_record_one_current ON adc.clinical_record_version (clinical_record_id) WHERE is_current;

UPDATE adc.appointment SET appointment_status='已到诊' WHERE appointment_id=901;
INSERT INTO adc.appointment
    (appointment_id, patient_id, schedule_id, schedule_version_no, starts_at, ends_at, treatment_request,
     manual_confirmation, appointment_status, reminder_arrangement, operated_by, operated_at)
VALUES (905,88001,87001,2,'2026-11-08 10:00Z','2026-11-08 10:27Z','SYNTHETIC current visit',
        'SYNTHETIC confirmation','已到诊','SYNTHETIC reminder','SYNTHETIC reception','2026-11-08 10:00Z');
INSERT INTO adc.visit
    (visit_id, appointment_id, clinic_id, actual_dentist_id, registered_at, arrival_verification, registered_by)
VALUES (41,901,81001,79001,'2026-11-08 09:00Z','SYNTHETIC verified','SYNTHETIC reception'),
       (42,905,81001,79001,'2026-11-08 10:00Z','SYNTHETIC verified','SYNTHETIC reception');
INSERT INTO adc.patient_health_change
    (health_change_id, visit_id, medical_history, change_content, verified_dentist_id, changed_at)
VALUES (400,41,'SYNTHETIC prior history','SYNTHETIC prior verification',79001,'2026-11-08 09:00Z');
INSERT INTO adc.treatment_consent_result
    (consent_result_id, visit_id, communication_party, proposed_treatment, consent_result, recorded_by,
     recorded_at, recording_dentist_id)
VALUES (51,41,'SYNTHETIC patient','SYNTHETIC treatment','同意','SYNTHETIC dentist','2026-11-08 09:02Z',79001);
INSERT INTO adc.clinical_record (clinical_record_id, visit_id) VALUES (61,41);
INSERT INTO adc.clinical_record_version
    (clinical_record_id, record_version_no, consent_result_id, responsible_dentist_id, performed_treatment,
     billing_unit, actual_quantity, implementation_result, revision_basis, recorded_by, recorded_at, is_current)
VALUES (61,1,51,79001,'SYNTHETIC old version','项',1,'已实施',NULL,'SYNTHETIC dentist','2026-11-08 09:12Z',false),
       (61,2,51,79001,'SYNTHETIC current treatment','项',1,'已实施','SYNTHETIC correction',
        'SYNTHETIC dentist','2026-11-08 09:20Z',true);
GRANT SELECT ON adc.patient_health_change, adc.clinical_record, adc.clinical_record_version TO p0_t028_runtime;
GRANT INSERT ON adc.patient_health_change TO p0_t028_runtime;
