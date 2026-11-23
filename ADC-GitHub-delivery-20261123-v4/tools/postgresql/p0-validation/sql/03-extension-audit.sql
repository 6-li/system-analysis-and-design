-- REVIEW ONLY. NOT EXECUTED. NOT A MIGRATION.
CREATE TABLE adc.extension_operation_audit_event (
    audit_event_id uuid NOT NULL,
    request_reference varchar(200) COLLATE "C" NOT NULL,
    event_sequence_no integer NOT NULL,
    operation_code varchar(40) NOT NULL,
    actor_reference varchar(100) NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    authorization_context jsonb NOT NULL,
    business_reference jsonb,
    outcome varchar(40) NOT NULL,
    reason text,
    evidence jsonb NOT NULL,
    CONSTRAINT pk_extension_audit PRIMARY KEY (audit_event_id),
    CONSTRAINT uq_extension_request_sequence
        UNIQUE (request_reference, event_sequence_no),
    CONSTRAINT ck_extension_request CHECK (
        request_reference ~ '^[A-Za-z0-9_:/.-]{1,200}$'),
    CONSTRAINT ck_extension_sequence CHECK (event_sequence_no > 0),
    CONSTRAINT ck_extension_actor CHECK (
        actor_reference = btrim(actor_reference)
        AND actor_reference ~ '[^[:space:]]'),
    CONSTRAINT ck_extension_time CHECK (isfinite(occurred_at)),
    CONSTRAINT ck_extension_operation CHECK (operation_code IN (
        'DURATION_DRAFT_CREATE','DURATION_DRAFT_EDIT','DURATION_PUBLISH')),
    CONSTRAINT ck_extension_outcome CHECK (outcome IN (
        'STARTED','SUCCEEDED','REJECTED','FAILED','UNKNOWN','OBSERVED')),
    CONSTRAINT ck_extension_auth CHECK ((
        jsonb_typeof(authorization_context) = 'object'
        AND authorization_context ?& ARRAY[
            'schemaVersion','policyVersion','evaluatedAction','decision','actualGrantedScope']
        AND authorization_context->'schemaVersion' = '1'::jsonb
        AND jsonb_typeof(authorization_context->'policyVersion') = 'string'
        AND (authorization_context->>'policyVersion') ~ '[^[:space:]]'
        AND jsonb_typeof(authorization_context->'evaluatedAction') = 'string'
        AND authorization_context->>'evaluatedAction' = CASE
            WHEN operation_code = 'DURATION_PUBLISH' THEN 'DURATION_PUBLISH'
            ELSE 'DURATION_EDIT' END
        AND authorization_context->>'decision' IN ('NOT_EVALUATED','ALLOW','DENY')
        AND jsonb_typeof(authorization_context->'actualGrantedScope') = 'array'
    ) IS TRUE),
    CONSTRAINT ck_extension_business_ref CHECK (business_reference IS NULL OR ((
        jsonb_typeof(business_reference) = 'object'
        AND business_reference ?& ARRAY['projectId','versionNo']
        AND jsonb_typeof(business_reference->'projectId') = 'string'
        AND (business_reference->>'projectId') ~ '^[1-9][0-9]*$'
        AND jsonb_typeof(business_reference->'versionNo') = 'number'
        AND (business_reference->>'versionNo') ~ '^[1-9][0-9]*$'
    ) IS TRUE)),
    CONSTRAINT ck_extension_evidence CHECK ((
        jsonb_typeof(evidence) = 'object'
        AND evidence ?& ARRAY['schemaVersion','eventKind','logicalEventId',
            'requestPayloadHash','requestOwner','executionToken','phase',
            'before','after','verification','relatedEventIds','observation']
        AND evidence->'schemaVersion' = '1'::jsonb
        AND jsonb_typeof(evidence->'logicalEventId') = 'string'
        AND evidence->>'logicalEventId' = audit_event_id::text
        AND jsonb_typeof(evidence->'requestPayloadHash') = 'string'
        AND (evidence->>'requestPayloadHash') ~ '^[a-f0-9]{64}$'
        AND jsonb_typeof(evidence->'requestOwner') = 'string'
        AND (evidence->>'requestOwner') ~ '[^[:space:]]'
        AND jsonb_typeof(evidence->'executionToken') = 'string'
        AND (evidence->>'executionToken') ~
            '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
        AND jsonb_typeof(evidence->'before') IN ('object','null')
        AND jsonb_typeof(evidence->'after') IN ('object','null')
        AND jsonb_typeof(evidence->'verification') = 'object'
        AND jsonb_typeof(evidence->'relatedEventIds') = 'array'
        AND jsonb_typeof(evidence->'observation') IN ('object','null')
    ) IS TRUE),
    CONSTRAINT ck_extension_event_relation CHECK ((
        (evidence->>'eventKind' = 'START' AND evidence->>'phase' = 'START'
            AND outcome = 'STARTED' AND event_sequence_no = 1)
        OR (evidence->>'eventKind' = 'TERMINAL' AND event_sequence_no > 1 AND (
            (evidence->>'phase' = 'DRAFT_EDIT_SUCCESS' AND outcome = 'SUCCEEDED'
                AND operation_code IN ('DURATION_DRAFT_CREATE','DURATION_DRAFT_EDIT'))
            OR (evidence->>'phase' = 'PUBLISH_SUCCESS' AND outcome = 'SUCCEEDED'
                AND operation_code = 'DURATION_PUBLISH')
            OR (evidence->>'phase' = 'DENIED' AND outcome = 'REJECTED'
                AND authorization_context->>'decision' = 'DENY')
            OR (evidence->>'phase' = 'VALIDATION_REJECTED' AND outcome = 'REJECTED'
                AND authorization_context->>'decision' = 'ALLOW')
            OR (evidence->>'phase' = 'ROLLED_BACK' AND outcome = 'FAILED')
            OR (evidence->>'phase' = 'RECOVERY_CLOSED_NO_COMMIT' AND outcome = 'REJECTED')
        ))
        OR (evidence->>'eventKind' = 'OBSERVATION' AND event_sequence_no > 1 AND (
            (evidence->>'phase' = 'COMMIT_UNKNOWN' AND outcome = 'UNKNOWN')
            OR (evidence->>'phase' IN ('DUPLICATE_SAME','DUPLICATE_MISMATCH',
                'RECOVERY_CONFIRMED_SUCCESS','FAILURE_DETAIL_ADDED') AND outcome = 'OBSERVED')
        ))
    ) IS TRUE),
    CONSTRAINT ck_extension_success_document CHECK (outcome <> 'SUCCEEDED' OR ((
        authorization_context->>'decision' = 'ALLOW'
        AND business_reference IS NOT NULL
        AND jsonb_typeof(evidence->'before') = 'object'
        AND jsonb_typeof(evidence->'after') = 'object'
        AND (evidence->'before') ?& ARRAY['targetVersion','previousCurrentVersion']
        AND (evidence->'after') ?& ARRAY['targetVersion','previousCurrentVersion']
    ) IS TRUE)),
    CONSTRAINT ck_extension_reason CHECK (
        (reason IS NULL OR reason ~ '[^[:space:]]')
        AND (NOT (outcome IN ('FAILED','REJECTED','UNKNOWN')
            OR evidence->>'phase' = 'DUPLICATE_MISMATCH')
            OR (reason IS NOT NULL AND reason ~ '[^[:space:]]'))
    )
);

CREATE UNIQUE INDEX uq_extension_request_terminal
    ON adc.extension_operation_audit_event (request_reference)
    WHERE evidence->>'eventKind' = 'TERMINAL';
