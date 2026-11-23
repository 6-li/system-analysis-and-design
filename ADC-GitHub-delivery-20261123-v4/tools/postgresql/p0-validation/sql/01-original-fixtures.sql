-- Original table 24: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.clinic (
    clinic_id BIGINT NOT NULL,
    clinic_name VARCHAR(100) NOT NULL,
    PRIMARY KEY(clinic_id)
);

-- Original table 25: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.dentist (
    dentist_id BIGINT NOT NULL,
    practice_qualification TEXT NOT NULL,
    PRIMARY KEY(dentist_id)
);

-- Original table 27: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.patient (
    patient_id BIGINT NOT NULL,
    patient_name VARCHAR(100) NOT NULL,
    identity_information TEXT NOT NULL,
    contact_information TEXT NOT NULL,
    emergency_contact_information TEXT,
    representative_identity_information TEXT,
    representative_verification_information TEXT,
    PRIMARY KEY(patient_id)
);
-- REVIEW ONLY: fresh-target definition, NOT a migration.
CREATE TABLE adc.reception_schedule_version (
    schedule_id bigint NOT NULL,
    schedule_version_no integer NOT NULL,
    clinic_id bigint NOT NULL,
    dentist_id bigint NOT NULL,
    starts_at timestamptz(6) NOT NULL,
    ends_at timestamptz(6) NOT NULL,
    activation_status varchar(40) NOT NULL,
    coordination_result text,
    qualification_check_result text NOT NULL,
    conflict_check_result text NOT NULL,
    incomplete_reason text,
    activated_at timestamptz(6),
    CONSTRAINT pk_reception_schedule_version
        PRIMARY KEY (schedule_id, schedule_version_no),
    CONSTRAINT fk_schedule_clinic FOREIGN KEY (clinic_id)
        REFERENCES adc.clinic (clinic_id),
    CONSTRAINT fk_schedule_dentist FOREIGN KEY (dentist_id)
        REFERENCES adc.dentist (dentist_id),
    CONSTRAINT ck_schedule_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_schedule_finite CHECK (
        isfinite(starts_at) AND isfinite(ends_at)
        AND (activated_at IS NULL OR isfinite(activated_at))
    ),
    CONSTRAINT ck_schedule_status CHECK (
        activation_status IN ('DRAFT', 'EFFECTIVE', 'SUPERSEDED')
    ),
    CONSTRAINT ck_schedule_activation_consistency CHECK (
        (activation_status = 'DRAFT' AND activated_at IS NULL)
        OR
        (activation_status IN ('EFFECTIVE', 'SUPERSEDED')
         AND activated_at IS NOT NULL)
    ),
    CONSTRAINT ex_dentist_effective_window EXCLUDE USING gist (
        dentist_id adc.gist_int8_ops WITH =,
        tstzrange(starts_at, ends_at, '[)') WITH &&
    ) WHERE (activation_status = 'EFFECTIVE')
);

CREATE UNIQUE INDEX uq_schedule_current_effective
    ON adc.reception_schedule_version (schedule_id)
    WHERE activation_status = 'EFFECTIVE';

-- Original table 29: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.appointment (
    appointment_id BIGINT NOT NULL,
    patient_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    schedule_version_no INT NOT NULL,
    starts_at timestamptz(6) NOT NULL,
    ends_at timestamptz(6) NOT NULL,
    treatment_request TEXT NOT NULL,
    manual_confirmation TEXT NOT NULL,
    appointment_status VARCHAR(40) NOT NULL,
    no_show_verification TEXT,
    reminder_arrangement TEXT NOT NULL,
    cancellation_verification TEXT,
    operated_by VARCHAR(100) NOT NULL,
    operated_at timestamptz(6) NOT NULL,
    representative_verification_basis TEXT,
    arrival_pending_notes TEXT,
    PRIMARY KEY(appointment_id),
    FOREIGN KEY(patient_id) REFERENCES adc.patient(patient_id),
    FOREIGN KEY(schedule_id,schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no),
    CHECK(starts_at<ends_at)
);

-- Original table 31: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.visit (
    visit_id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    clinic_id BIGINT NOT NULL,
    actual_dentist_id BIGINT NOT NULL,
    registered_at timestamptz(6) NOT NULL,
    arrival_verification TEXT NOT NULL,
    registered_by VARCHAR(100) NOT NULL,
    arrived_at timestamptz(6),
    PRIMARY KEY(visit_id),
    UNIQUE(appointment_id),
    FOREIGN KEY(appointment_id) REFERENCES adc.appointment(appointment_id),
    FOREIGN KEY(clinic_id) REFERENCES adc.clinic(clinic_id),
    FOREIGN KEY(actual_dentist_id) REFERENCES adc.dentist(dentist_id)
);

-- Original table 51: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.appointment_change_history (
    appointment_change_id BIGINT NOT NULL,
    appointment_id BIGINT NOT NULL,
    old_schedule_id BIGINT NOT NULL,
    old_schedule_version_no INT NOT NULL,
    new_schedule_id BIGINT NOT NULL,
    new_schedule_version_no INT NOT NULL,
    old_starts_at timestamptz(6) NOT NULL,
    old_ends_at timestamptz(6) NOT NULL,
    new_starts_at timestamptz(6) NOT NULL,
    new_ends_at timestamptz(6) NOT NULL,
    old_treatment_request TEXT NOT NULL,
    new_treatment_request TEXT NOT NULL,
    manual_confirmation_basis TEXT NOT NULL,
    change_basis TEXT NOT NULL,
    verification_basis TEXT,
    old_reminder_basis TEXT NOT NULL,
    new_reminder_basis TEXT NOT NULL,
    changed_by VARCHAR(100) NOT NULL,
    changed_at timestamptz(6) NOT NULL,
    change_request_reference VARCHAR(200),
    PRIMARY KEY(appointment_change_id),
    FOREIGN KEY(appointment_id) REFERENCES adc.appointment(appointment_id),
    FOREIGN KEY(old_schedule_id,old_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no),
    FOREIGN KEY(new_schedule_id,new_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no)
);

-- Original table 28: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.appointment_resolution (
    resolution_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    schedule_version_no INT NOT NULL,
    appointment_id BIGINT NOT NULL,
    resolution_subject TEXT NOT NULL,
    resolution_result TEXT NOT NULL,
    resolution_basis TEXT,
    responsible_party TEXT NOT NULL,
    pending_confirmation TEXT,
    recorded_at timestamptz(6) NOT NULL,
    appointment_change_id BIGINT,
    PRIMARY KEY(resolution_id),
    FOREIGN KEY(schedule_id,schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no),
    FOREIGN KEY(appointment_id) REFERENCES adc.appointment(appointment_id),
    FOREIGN KEY(appointment_change_id) REFERENCES adc.appointment_change_history(appointment_change_id)
);

-- Original table 53: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.schedule_maintenance_issue (
    maintenance_issue_id BIGINT NOT NULL,
    request_reference VARCHAR(200) NOT NULL,
    clinic_id BIGINT NOT NULL,
    dentist_id BIGINT,
    related_schedule_id BIGINT,
    related_schedule_version_no INT,
    requested_starts_at timestamptz(6),
    requested_ends_at timestamptz(6),
    qualification_check_result TEXT,
    conflict_check_result TEXT,
    coordination_result TEXT,
    failure_reason TEXT NOT NULL,
    request_context TEXT NOT NULL,
    recorded_by VARCHAR(100) NOT NULL,
    recorded_at timestamptz(6) NOT NULL,
    previous_issue_id BIGINT,
    resulting_schedule_id BIGINT,
    resulting_schedule_version_no INT,
    PRIMARY KEY(maintenance_issue_id),
    UNIQUE(request_reference),
    FOREIGN KEY(clinic_id) REFERENCES adc.clinic(clinic_id),
    FOREIGN KEY(dentist_id) REFERENCES adc.dentist(dentist_id),
    FOREIGN KEY(related_schedule_id,related_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no) MATCH FULL,
    FOREIGN KEY(resulting_schedule_id,resulting_schedule_version_no) REFERENCES adc.reception_schedule_version(schedule_id,schedule_version_no) MATCH FULL,
    FOREIGN KEY(previous_issue_id) REFERENCES adc.schedule_maintenance_issue(maintenance_issue_id)
);

-- Original table 54: isolated fixture, NOT a change to report/model.
CREATE TABLE adc.operation_audit_event (
    audit_event_id BIGINT NOT NULL,
    request_reference VARCHAR(200) NOT NULL,
    event_sequence_no INT NOT NULL,
    uc_code VARCHAR(40) NOT NULL,
    operation_name VARCHAR(100) NOT NULL,
    actor_reference VARCHAR(100) NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    authorization_scope TEXT NOT NULL,
    business_reference VARCHAR(200),
    outcome VARCHAR(40) NOT NULL,
    reason TEXT,
    evidence TEXT,
    PRIMARY KEY(audit_event_id),
    UNIQUE(request_reference,event_sequence_no),
    CHECK(event_sequence_no>0),
    CHECK(uc_code ~ '^UC(0[1-9]|1[0-9]|2[0-2])$')
);
