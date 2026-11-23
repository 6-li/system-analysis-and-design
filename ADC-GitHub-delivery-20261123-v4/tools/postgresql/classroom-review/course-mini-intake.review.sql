-- Candidate for an independently identified, empty local course validation cluster.
-- Apply after the existing adc.patient definition. Provision the generated login credential separately.
BEGIN;
DO $$
BEGIN
    IF current_database() <> 'adc_course_validation'
       OR host(inet_server_addr()) IS DISTINCT FROM '127.0.0.1'
       OR inet_server_port() IS NULL OR inet_server_port() = 5432 THEN
        RAISE EXCEPTION 'Wrong course target';
    END IF;
END
$$;

CREATE SCHEMA adc_mini;
REVOKE ALL ON SCHEMA adc_mini FROM PUBLIC;
CREATE TABLE adc_mini.preliminary_request (
    request_id uuid PRIMARY KEY,
    applicant_account text NOT NULL,
    submission_key text NOT NULL,
    payload_hash text NOT NULL CHECK (payload_hash ~ '^[a-f0-9]{64}$'),
    submitted_content jsonb NOT NULL,
    received_at timestamptz(6) NOT NULL,
    request_version integer NOT NULL DEFAULT 0 CHECK (request_version >= 0),
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

CREATE ROLE adc_course_mini_intake LOGIN NOINHERIT NOSUPERUSER
    NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT CONNECT ON DATABASE adc_course_validation TO adc_course_mini_intake;
GRANT USAGE ON SCHEMA adc_mini TO adc_course_mini_intake;
GRANT USAGE ON SCHEMA adc TO adc_course_mini_intake;
GRANT SELECT (clinic_id, clinic_name) ON adc.clinic TO adc_course_mini_intake;
GRANT SELECT (request_id, payload_hash, applicant_account, submission_key),
      INSERT (request_id, applicant_account, submission_key, payload_hash, submitted_content,
              received_at, associated_patient_id)
    ON adc_mini.preliminary_request TO adc_course_mini_intake;
GRANT SELECT (request_id, operation), INSERT (request_id, operation, event_at)
    ON adc_mini.operation_audit_event TO adc_course_mini_intake;
ROLLBACK;
