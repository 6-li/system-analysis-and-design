-- UC19 classroom review only. Requires isolated UC17 tables.
BEGIN;

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

GRANT SELECT ON adc.quarterly_dentist_performance_stat TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.quarterly_operating_calculation_version TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.quarterly_dentist_performance_calc TO p0_t028_runtime;

ROLLBACK;
