DO $check$
DECLARE
    d record;
BEGIN
    IF (SELECT count(*) FROM pg_database WHERE datname IN ('adc_dev', 'adc_test')) <> 2 THEN
        RAISE EXCEPTION 'Both ADC databases must exist; do not repair by automatic deletion/recreation';
    END IF;
    FOR d IN SELECT * FROM pg_database WHERE datname IN ('adc_dev', 'adc_test') LOOP
        IF pg_get_userbyid(d.datdba) <> 'postgres'
           OR d.datallowconn OR d.datistemplate OR d.datconnlimit <> -1
           OR pg_encoding_to_char(d.encoding) <> 'UTF8'
           OR d.datlocprovider <> 'c'
           OR d.datcollate <> 'Chinese (Simplified)_China.936'
           OR d.datctype <> 'Chinese (Simplified)_China.936'
           OR d.datlocale IS NOT NULL THEN
            RAISE EXCEPTION 'Unexpected database owner, open connections, or locale configuration: %', d.datname;
        END IF;
        IF d.datcollversion IS DISTINCT FROM pg_database_collation_actual_version(d.oid) THEN
            RAISE EXCEPTION 'Recorded and actual collation versions differ: %; do not refresh automatically', d.datname;
        END IF;
        IF EXISTS (
            SELECT 1 FROM pg_db_role_setting WHERE setdatabase = d.oid
        ) THEN
            RAISE EXCEPTION 'Unexpected database-specific settings: %', d.datname;
        END IF;
    END LOOP;
END;
$check$;
