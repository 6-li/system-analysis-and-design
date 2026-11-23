-- UC17 classroom review only. Requires the isolated clinic and dentist tables.
BEGIN;

CREATE TABLE adc.quarterly_operating_statistic (
    statistic_id bigint PRIMARY KEY,
    quarter_code varchar(16) NOT NULL,
    accounting_basis text NOT NULL,
    source_data_version varchar(200) NOT NULL,
    source_basis text NOT NULL,
    receivable_amount numeric(18,2),
    received_amount numeric(18,2),
    operating_expense numeric(18,2),
    consumption_cost numeric(18,2),
    due_appointment_count integer,
    confirmed_no_show_count integer,
    no_show_rate numeric(9,6),
    aggregation_readiness text NOT NULL,
    generated_at timestamptz NOT NULL,
    cancelled_appointment_count integer,
    source_manifest_reference varchar(200) NOT NULL
);

CREATE TABLE adc.quarterly_statistic_clinic (
    statistic_id bigint NOT NULL REFERENCES adc.quarterly_operating_statistic (statistic_id),
    clinic_id bigint NOT NULL REFERENCES adc.clinic (clinic_id),
    PRIMARY KEY (statistic_id, clinic_id)
);

CREATE TABLE adc.quarterly_dentist_performance_stat (
    statistic_id bigint NOT NULL REFERENCES adc.quarterly_operating_statistic (statistic_id),
    dentist_id bigint NOT NULL REFERENCES adc.dentist (dentist_id),
    performance_amount numeric(18,2) NOT NULL,
    attribution_basis text NOT NULL,
    PRIMARY KEY (statistic_id, dentist_id)
);

CREATE TABLE adc.quarterly_data_issue (
    issue_id bigint PRIMARY KEY,
    statistic_id bigint NOT NULL REFERENCES adc.quarterly_operating_statistic (statistic_id),
    clinic_id bigint,
    original_data text NOT NULL,
    corrected_value text,
    correction_basis text,
    business_attribution text,
    corrected_by varchar(100),
    correction_status varchar(40) NOT NULL,
    consistency_check_result text,
    FOREIGN KEY (statistic_id, clinic_id)
        REFERENCES adc.quarterly_statistic_clinic (statistic_id, clinic_id)
);

GRANT SELECT, INSERT ON adc.quarterly_operating_statistic, adc.quarterly_statistic_clinic
    TO p0_t028_runtime;
GRANT INSERT ON adc.quarterly_dentist_performance_stat, adc.quarterly_data_issue
    TO p0_t028_runtime;

ROLLBACK;
