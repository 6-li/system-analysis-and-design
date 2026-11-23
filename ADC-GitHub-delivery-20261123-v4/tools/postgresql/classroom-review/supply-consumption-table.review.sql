-- UC16 classroom review only. Requires the isolated UC13 stock table.
BEGIN;

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

GRANT SELECT, INSERT ON adc.supply_consumption TO p0_t028_runtime;
GRANT UPDATE (on_hand_quantity) ON adc.medical_supply_stock TO p0_t028_runtime;

ROLLBACK;
