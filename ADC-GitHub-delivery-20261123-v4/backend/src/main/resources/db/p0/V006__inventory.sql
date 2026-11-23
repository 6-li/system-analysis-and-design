-- Source: tools/postgresql/classroom-review/stock-inquiry-tables.review.sql
-- UC13 classroom review only. The isolated fixture must already contain adc.clinic.

CREATE TABLE adc.medical_supply (
    supply_id bigint PRIMARY KEY,
    supply_name varchar(100) NOT NULL,
    stock_unit varchar(40) NOT NULL
);

CREATE TABLE adc.medical_supply_stock (
    stock_id bigint PRIMARY KEY,
    clinic_id bigint NOT NULL REFERENCES adc.clinic (clinic_id),
    supply_id bigint NOT NULL REFERENCES adc.medical_supply (supply_id),
    on_hand_quantity numeric(18,3) NOT NULL,
    reorder_threshold numeric(18,3),
    existing_warning text,
    UNIQUE (clinic_id, supply_id)
);

-- Source: tools/postgresql/classroom-review/purchase-requirement-table.review.sql
-- UC14 classroom review only. Requires the isolated clinic and UC13 supply fixtures.

CREATE TABLE adc.purchase_requirement (
    purchase_requirement_id bigint PRIMARY KEY,
    clinic_id bigint NOT NULL REFERENCES adc.clinic (clinic_id),
    supply_id bigint NOT NULL REFERENCES adc.medical_supply (supply_id),
    required_quantity numeric(18,3) NOT NULL,
    requirement_confirmation text NOT NULL,
    procurement_status varchar(40) NOT NULL,
    unavailable_supply_explanation text,
    registered_by varchar(100) NOT NULL,
    registered_at timestamptz NOT NULL,
    CHECK (procurement_status <> '无法供货待调整'
        OR (unavailable_supply_explanation IS NOT NULL AND btrim(unavailable_supply_explanation) <> ''))
);

-- Source: tools/postgresql/classroom-review/supply-receipt-table.review.sql
-- UC15 classroom review only. Requires the isolated UC13 and UC14 tables.

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

-- Source: tools/postgresql/classroom-review/supply-consumption-table.review.sql
-- UC16 classroom review only. Requires the isolated UC13 stock table.

CREATE TABLE adc.supply_consumption (
    consumption_id bigint PRIMARY KEY,
    stock_id bigint NOT NULL REFERENCES adc.medical_supply_stock (stock_id),
    consumed_quantity numeric(18,3) NOT NULL,
    consumption_cost numeric(18,2) NOT NULL,
    cost_basis text NOT NULL,
    verified_by varchar(100) NOT NULL,
    occurred_at timestamptz NOT NULL,
    registered_at timestamptz NOT NULL,
    registered_by varchar(100) NOT NULL,
    consumption_source_reference varchar(200)
);
