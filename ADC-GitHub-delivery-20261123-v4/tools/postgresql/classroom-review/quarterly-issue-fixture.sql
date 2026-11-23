-- Synthetic existing preview and issue for the isolated UC18 run.
INSERT INTO adc.quarterly_operating_statistic
    (statistic_id, quarter_code, accounting_basis, source_data_version, source_basis,
     aggregation_readiness, generated_at, source_manifest_reference)
VALUES (18001, '2026-Q4', 'SYNTHETIC basis', 'SYNTHETIC v1',
        '{"version":"SYNTHETIC v1","facts":[{"id":"expense-1","version":"1","clinic":81001,"amount":null}]}',
        '待核实', '2026-11-17T00:00:00Z', 'quarterly_operating_statistic/18001/source_basis');

INSERT INTO adc.quarterly_statistic_clinic (statistic_id, clinic_id)
VALUES (18001, 81001), (18001, 81002);

INSERT INTO adc.quarterly_data_issue (issue_id, statistic_id, clinic_id, original_data, correction_status)
VALUES (18002, 18001, 81001, 'SYNTHETIC source amount missing', '待核实');
