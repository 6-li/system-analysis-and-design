-- Course migration: four clinical prerequisite tables and four billing tables. No seed records.
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


-- Source: tools/postgresql/classroom-review/billing-tables.review.sql
-- Review candidate only. Deliberately roll back if executed as written.
-- Prerequisites: adc.visit and adc.clinical_record_version with their existing primary keys.

CREATE TABLE adc.treatment_invoice (
    invoice_id bigint PRIMARY KEY,
    visit_id bigint NOT NULL UNIQUE REFERENCES adc.visit (visit_id),
    invoice_status varchar(40) NOT NULL
);

CREATE TABLE adc.treatment_invoice_version (
    invoice_id bigint NOT NULL REFERENCES adc.treatment_invoice (invoice_id),
    invoice_version_no integer NOT NULL,
    total_amount numeric(18,2) NOT NULL,
    billing_question text,
    handling_basis text,
    confirmation_information text,
    confirmed_at timestamptz(6),
    is_current boolean NOT NULL,
    PRIMARY KEY (invoice_id, invoice_version_no),
    CHECK ((confirmation_information IS NULL) = (confirmed_at IS NULL))
);

CREATE UNIQUE INDEX treatment_invoice_one_current
    ON adc.treatment_invoice_version (invoice_id) WHERE is_current;

CREATE TABLE adc.treatment_invoice_item (
    invoice_item_id bigint PRIMARY KEY,
    invoice_id bigint NOT NULL,
    invoice_version_no integer NOT NULL,
    clinical_record_id bigint NOT NULL,
    record_version_no integer NOT NULL,
    billed_item varchar(200) NOT NULL,
    billing_unit varchar(40) NOT NULL,
    billed_quantity numeric(18,3) NOT NULL CHECK (billed_quantity > 0),
    unit_price numeric(18,2) NOT NULL CHECK (unit_price >= 0),
    line_amount numeric(18,2) NOT NULL CHECK (line_amount >= 0),
    price_basis text NOT NULL,
    FOREIGN KEY (invoice_id, invoice_version_no)
        REFERENCES adc.treatment_invoice_version (invoice_id, invoice_version_no),
    FOREIGN KEY (clinical_record_id, record_version_no)
        REFERENCES adc.clinical_record_version (clinical_record_id, record_version_no)
);

CREATE TABLE adc.payment_transaction (
    payment_id bigint PRIMARY KEY,
    invoice_id bigint NOT NULL,
    invoice_version_no integer NOT NULL,
    payment_method varchar(40) NOT NULL,
    transaction_result varchar(40),
    requested_amount numeric(18,2) NOT NULL,
    received_amount numeric(18,2),
    receipt_basis text,
    verification_result text,
    local_settlement_status varchar(40) NOT NULL,
    received_at timestamptz(6),
    external_transaction_reference varchar(200),
    operated_by varchar(100) NOT NULL,
    recorded_at timestamptz(6) NOT NULL,
    FOREIGN KEY (invoice_id, invoice_version_no)
        REFERENCES adc.treatment_invoice_version (invoice_id, invoice_version_no),
    CHECK ((received_amount IS NULL) = (received_at IS NULL)),
    CHECK (received_amount IS NULL OR (
        receipt_basis IS NOT NULL AND verification_result IS NOT NULL AND transaction_result IS NOT NULL
    ))
);
-- Source: tools/postgresql/classroom-review/invoice-question-grants.review.sql
-- UC11 classroom review only, for the disposable classroom runtime role.
