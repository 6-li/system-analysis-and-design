CREATE TABLE adc.project_duration_version (
 project_id bigint NOT NULL CHECK(project_id>0),
 duration_version_no integer NOT NULL CHECK(duration_version_no>0),
 project_name varchar(200) NOT NULL CHECK(length(btrim(project_name))>0),
 duration_minutes integer NOT NULL CHECK(duration_minutes>0),
 publication_status varchar(40) NOT NULL CHECK(publication_status IN ('DRAFT','CURRENT','SUPERSEDED')),
 published_at timestamptz(6), published_by varchar(100), publication_evidence jsonb,
 PRIMARY KEY(project_id,duration_version_no),
 CHECK ((publication_status='DRAFT' AND published_at IS NULL AND published_by IS NULL AND publication_evidence IS NULL)
 OR (publication_status IN ('CURRENT','SUPERSEDED') AND published_at IS NOT NULL AND isfinite(published_at)
 AND published_by IS NOT NULL AND published_by ~ '[^[:space:]]' AND publication_evidence IS NOT NULL AND ((
 jsonb_typeof(publication_evidence)='object'
 AND publication_evidence ?& ARRAY['schemaVersion','requestReference','canonicalPayloadHash']
 AND publication_evidence->'schemaVersion'='1'::jsonb
 AND jsonb_typeof(publication_evidence->'requestReference')='string'
 AND (publication_evidence->>'requestReference') ~ '^[A-Za-z0-9_:/.-]{1,200}$'
 AND jsonb_typeof(publication_evidence->'canonicalPayloadHash')='string'
 AND (publication_evidence->>'canonicalPayloadHash') ~ '^[a-f0-9]{64}$'
 ) IS TRUE)))
);
CREATE UNIQUE INDEX uq_duration_project_current ON adc.project_duration_version(project_id) WHERE publication_status='CURRENT';
CREATE UNIQUE INDEX uq_duration_publication_request ON adc.project_duration_version((publication_evidence->>'requestReference') COLLATE "C") WHERE publication_status IN ('CURRENT','SUPERSEDED');
