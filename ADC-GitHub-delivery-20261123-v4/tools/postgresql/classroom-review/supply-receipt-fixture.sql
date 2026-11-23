-- Approved-runtime validation input, to be loaded only after approval of UC15.
INSERT INTO adc.purchase_requirement
    (purchase_requirement_id, clinic_id, supply_id, required_quantity, requirement_confirmation,
     procurement_status, registered_by, registered_at)
VALUES (801, 81001, 101, 6.000, 'SYNTHETIC confirmed demand', '待联系供应商',
        'SYNTHETIC procurement', '2026-11-17T00:00:00Z');
