DO $check$
DECLARE t record; actual_version text;
BEGIN
 IF current_setting('transaction_read_only') IS DISTINCT FROM 'on' THEN
  RAISE EXCEPTION 'Preflight requires read-only transaction';
 END IF;
 IF (SELECT count(*) FROM pg_catalog.pg_database WHERE datname='template0') <> 1 THEN
  RAISE EXCEPTION 'Exactly one template0 is required';
 END IF;
 SELECT * INTO STRICT t FROM pg_catalog.pg_database WHERE datname='template0';
 IF pg_catalog.pg_encoding_to_char(t.encoding) IS DISTINCT FROM 'UTF8'
 OR t.datlocprovider IS DISTINCT FROM 'c'
 OR t.datcollate IS DISTINCT FROM 'Chinese (Simplified)_China.936'
 OR t.datctype IS DISTINCT FROM 'Chinese (Simplified)_China.936'
 OR t.datlocale IS NOT NULL OR t.datistemplate IS DISTINCT FROM true
 OR t.datallowconn IS DISTINCT FROM false THEN
  RAISE EXCEPTION 'template0 differs from reviewed B1 baseline';
 END IF;
 actual_version := pg_catalog.pg_database_collation_actual_version(t.oid);
 IF t.datcollversion IS NOT NULL AND t.datcollversion IS DISTINCT FROM actual_version THEN
  RAISE EXCEPTION 'template0 collation version mismatch; do not refresh';
 END IF;
END;
$check$;
