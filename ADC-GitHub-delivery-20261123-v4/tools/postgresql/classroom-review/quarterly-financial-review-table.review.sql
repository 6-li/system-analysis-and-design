-- UC20 classroom review only. Requires the isolated UC19 calculation tables.
BEGIN;

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

GRANT SELECT, INSERT ON adc.financial_review TO p0_t028_runtime;

ROLLBACK;
