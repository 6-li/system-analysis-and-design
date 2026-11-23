-- UC14 classroom review only. Requires the isolated clinic and UC13 supply fixtures.
BEGIN;

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

GRANT SELECT (clinic_id) ON adc.clinic TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.purchase_requirement TO p0_t028_runtime;
GRANT UPDATE (procurement_status, unavailable_supply_explanation)
    ON adc.purchase_requirement TO p0_t028_runtime;

ROLLBACK;
