GRANT SELECT, INSERT ON adc.treatment_invoice, adc.treatment_invoice_version,
    adc.treatment_invoice_item, adc.payment_transaction TO p0_t028_runtime;
GRANT UPDATE (invoice_status) ON adc.treatment_invoice TO p0_t028_runtime;
GRANT UPDATE (confirmation_information, confirmed_at) ON adc.treatment_invoice_version TO p0_t028_runtime;
GRANT UPDATE (transaction_result, received_amount, received_at, receipt_basis, verification_result,
    external_transaction_reference, local_settlement_status) ON adc.payment_transaction TO p0_t028_runtime;
GRANT SELECT, INSERT ON adc.treatment_consent_result TO p0_t028_runtime;
GRANT INSERT ON adc.clinical_record, adc.clinical_record_version TO p0_t028_runtime;
