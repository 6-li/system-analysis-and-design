-- UC15 classroom review only. Requires the isolated UC13 and UC14 tables.
BEGIN;

CREATE TABLE adc.supply_receipt (
    receipt_id bigint PRIMARY KEY,
    purchase_requirement_id bigint NOT NULL REFERENCES adc.purchase_requirement (purchase_requirement_id),
    stock_id bigint REFERENCES adc.medical_supply_stock (stock_id),
    delivery_reference varchar(200),
    acceptance_result text NOT NULL,
    delivery_discrepancy text,
    discrepancy_status varchar(40) NOT NULL,
    received_quantity numeric(18,3),
    purchase_cost numeric(18,2),
    expiry_date date,
    registered_by varchar(100) NOT NULL,
    registered_at timestamptz NOT NULL,
    CHECK (discrepancy_status <> '已入库' OR
        (stock_id IS NOT NULL AND received_quantity IS NOT NULL
            AND purchase_cost IS NOT NULL AND expiry_date IS NOT NULL)),
    CHECK (discrepancy_status <> '差异待处理' OR
        (delivery_discrepancy IS NOT NULL AND btrim(delivery_discrepancy) <> ''
            AND stock_id IS NULL AND received_quantity IS NULL
            AND purchase_cost IS NULL AND expiry_date IS NULL))
);

GRANT SELECT, INSERT ON adc.supply_receipt TO p0_t028_runtime;
GRANT UPDATE (stock_id, delivery_reference, acceptance_result, delivery_discrepancy,
    discrepancy_status, received_quantity, purchase_cost, expiry_date, registered_by, registered_at)
    ON adc.supply_receipt TO p0_t028_runtime;
GRANT UPDATE (on_hand_quantity) ON adc.medical_supply_stock TO p0_t028_runtime;

ROLLBACK;
