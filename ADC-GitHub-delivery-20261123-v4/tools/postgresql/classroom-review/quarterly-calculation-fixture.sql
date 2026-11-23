-- Synthetic ready preview, loaded only in the authorized isolated UC19 run.
INSERT INTO adc.quarterly_operating_statistic
    (statistic_id, quarter_code, accounting_basis, source_data_version, source_basis,
     receivable_amount, received_amount, operating_expense, consumption_cost,
     due_appointment_count, confirmed_no_show_count, no_show_rate, cancelled_appointment_count,
     aggregation_readiness, generated_at, source_manifest_reference)
VALUES (19001, '2026-Q4', '{"rules":"SYNTHETIC confirmed basis"}', 'SYNTHETIC-v1',
        '{"version":"SYNTHETIC-v1","facts":[
            {"id":"invoice-1","version":"1","clinic":81001,"metric":"RECEIVABLE","amount":"45.00"},
            {"id":"payment-1","version":"1","clinic":81001,"metric":"RECEIVED","amount":"33.33"},
            {"id":"expense-1","version":"1","clinic":81001,"metric":"EXPENSE","amount":"9.00"},
            {"id":"consumption-1","version":"1","clinic":81001,"metric":"CONSUMPTION","amount":"5.75"},
            {"id":"visit-1","version":"1","clinic":81001,"metric":"PERFORMANCE","amount":"12.00","dentist":79001}
        ]}',
        45.00, 33.33, 9.00, 5.75, 0, 0, NULL, 0,
        '达到汇总要求', '2027-01-01T00:00:00Z', 'quarterly_operating_statistic/19001/source_basis');

INSERT INTO adc.quarterly_statistic_clinic (statistic_id, clinic_id) VALUES (19001, 81001);
INSERT INTO adc.quarterly_dentist_performance_stat
    (statistic_id, dentist_id, performance_amount, attribution_basis)
VALUES (19001, 79001, 12.00, 'SYNTHETIC attribution');
