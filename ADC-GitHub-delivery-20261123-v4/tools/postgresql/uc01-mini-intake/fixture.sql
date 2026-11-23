-- Synthetic UC01 intake verification only. No deployment migration.
CREATE SCHEMA adc_mini;
REVOKE ALL ON SCHEMA adc_mini FROM PUBLIC;
CREATE TABLE adc_mini.preliminary_request (
    request_id uuid PRIMARY KEY,
    applicant_account text NOT NULL,
    submission_key text NOT NULL,
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    submitted_content jsonb NOT NULL,
    received_at timestamptz(6) NOT NULL,
    request_version integer NOT NULL DEFAULT 0 CHECK (request_version>=0),
    associated_patient_id bigint REFERENCES adc.patient(patient_id),
    UNIQUE (applicant_account, submission_key)
);
CREATE TABLE adc_mini.operation_audit_event (
    request_id uuid NOT NULL REFERENCES adc_mini.preliminary_request(request_id),
    operation text NOT NULL,
    event_at timestamptz(6) NOT NULL,
    event_id uuid UNIQUE,
    original_request_reference text,
    PRIMARY KEY (request_id, operation)
);
CREATE FUNCTION adc_mini.test_reject_intake_audit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM adc_mini.preliminary_request
               WHERE request_id=NEW.request_id AND submission_key='SYNTHETIC-force-audit-failure') THEN
        RAISE EXCEPTION USING ERRCODE='23514', MESSAGE='SYNTHETIC intake audit failure';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER test_reject_intake_audit BEFORE INSERT ON adc_mini.operation_audit_event
    FOR EACH ROW EXECUTE FUNCTION adc_mini.test_reject_intake_audit();
GRANT USAGE ON SCHEMA adc_mini TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc_mini.preliminary_request, adc_mini.operation_audit_event TO p0_t028_runtime;
GRANT UPDATE (request_version, associated_patient_id) ON adc_mini.preliminary_request TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.patient TO p0_t028_runtime;
