-- UC11 classroom review only, for the disposable classroom runtime role.
BEGIN;

GRANT UPDATE (billing_question, handling_basis, is_current)
    ON adc.treatment_invoice_version TO p0_t028_runtime;
GRANT UPDATE (is_current)
    ON adc.clinical_record_version TO p0_t028_runtime;

ROLLBACK;
