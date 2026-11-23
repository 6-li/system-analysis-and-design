-- This opt-in is an execution convention, NOT proof of human approval.
-- Never run as a standalone file. Call only from an approved entry stage.
\if :{?adc_approved_stage}
\else
DO $guard$
BEGIN
    RAISE EXCEPTION 'Missing adc_approved_stage; actual writes require explicit user approval';
END;
$guard$;
\endif

-- psql expands these as SQL literals, outside a dollar-quoted body.
SELECT :'adc_approved_stage' = :'adc_expected_stage' AS adc_stage_matches
\gset
\if :adc_stage_matches
\else
DO $guard$
BEGIN
    RAISE EXCEPTION 'Approval stage does not match this entry; no writes permitted';
END;
$guard$;
\endif
