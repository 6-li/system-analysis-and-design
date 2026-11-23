-- UC13 classroom review only. The isolated fixture must already contain adc.clinic.
BEGIN;

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

ROLLBACK;
