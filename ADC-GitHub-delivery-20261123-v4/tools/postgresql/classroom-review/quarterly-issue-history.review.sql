-- UC18 classroom review only. Requires the isolated UC17 tables.
BEGIN;

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

GRANT SELECT ON adc.quarterly_data_issue, adc.quarterly_issue_correction_history TO p0_t028_runtime;
GRANT UPDATE (corrected_value, correction_basis, business_attribution, corrected_by,
    correction_status, consistency_check_result) ON adc.quarterly_data_issue TO p0_t028_runtime;
GRANT INSERT ON adc.quarterly_issue_correction_history TO p0_t028_runtime;
GRANT UPDATE (consistency_check_result, check_basis, checked_at, correction_status)
    ON adc.quarterly_issue_correction_history TO p0_t028_runtime;

ROLLBACK;
