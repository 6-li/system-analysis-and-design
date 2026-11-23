-- Source: tools/postgresql/classroom-review/quarterly-preview-tables.review.sql
-- UC17 classroom review only. Requires the isolated clinic and dentist tables.

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

-- Source: tools/postgresql/classroom-review/quarterly-issue-history.review.sql
-- UC18 classroom review only. Requires the isolated UC17 tables.

CREATE TABLE adc.quarterly_issue_correction_history (
    correction_history_id bigint PRIMARY KEY,
    issue_id bigint NOT NULL REFERENCES adc.quarterly_data_issue (issue_id),
    previous_value text,
    corrected_value text NOT NULL,
    correction_basis text NOT NULL,
    business_attribution text,
    corrected_by varchar(100) NOT NULL,
    corrected_at timestamptz NOT NULL,
    correction_status varchar(40) NOT NULL,
    consistency_check_result text,
    check_basis text,
    checked_at timestamptz,
    resulting_statistic_id bigint REFERENCES adc.quarterly_operating_statistic (statistic_id),
    correction_request_reference varchar(200),
    CHECK (
        (consistency_check_result IS NULL AND check_basis IS NULL AND checked_at IS NULL)
        OR (consistency_check_result IS NOT NULL AND check_basis IS NOT NULL AND checked_at IS NOT NULL)
    )
);

-- Source: tools/postgresql/classroom-review/quarterly-calculation-tables.review.sql
-- UC19 classroom review only. Requires isolated UC17 tables.

CREATE TABLE adc.quarterly_operating_calculation_version (
    calculation_id bigint NOT NULL,
    calculation_version_no integer NOT NULL,
    statistic_id bigint NOT NULL REFERENCES adc.quarterly_operating_statistic (statistic_id),
    calculation_basis text,
    net_profit numeric(18,2),
    incentive_pool_amount numeric(18,2),
    report_status varchar(40) NOT NULL,
    pending_verification_notes text,
    generated_by varchar(100) NOT NULL,
    generated_at timestamptz NOT NULL,
    PRIMARY KEY (calculation_id, calculation_version_no)
);

CREATE TABLE adc.quarterly_dentist_performance_calc (
    calculation_id bigint NOT NULL,
    calculation_version_no integer NOT NULL,
    dentist_id bigint NOT NULL REFERENCES adc.dentist (dentist_id),
    performance_amount numeric(18,2) NOT NULL,
    attribution_basis text NOT NULL,
    PRIMARY KEY (calculation_id, calculation_version_no, dentist_id),
    FOREIGN KEY (calculation_id, calculation_version_no)
        REFERENCES adc.quarterly_operating_calculation_version (calculation_id, calculation_version_no)
);

-- Source: tools/postgresql/classroom-review/quarterly-financial-review-table.review.sql
-- UC20 classroom review only. Requires the isolated UC19 calculation tables.

CREATE TABLE adc.financial_review (
    review_id bigint PRIMARY KEY,
    calculation_id bigint NOT NULL,
    calculation_version_no integer NOT NULL,
    review_result text NOT NULL,
    review_notes text NOT NULL,
    reviewed_by varchar(100) NOT NULL,
    source_basis text NOT NULL,
    reviewed_at timestamptz NOT NULL,
    FOREIGN KEY (calculation_id, calculation_version_no)
        REFERENCES adc.quarterly_operating_calculation_version (calculation_id, calculation_version_no)
);
