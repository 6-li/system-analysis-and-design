-- Synthetic data for the dedicated, isolated UC13 run.
INSERT INTO adc.medical_supply (supply_id, supply_name, stock_unit)
VALUES (101, 'SYNTHETIC gloves', '盒');
INSERT INTO adc.medical_supply_stock
    (stock_id, clinic_id, supply_id, on_hand_quantity, reorder_threshold, existing_warning)
VALUES (701, 81001, 101, 4.000, NULL, 'SYNTHETIC expiry warning'),
       (702, 81002, 101, 9.000, NULL, NULL);
GRANT SELECT ON adc.medical_supply, adc.medical_supply_stock TO p0_t028_runtime;
