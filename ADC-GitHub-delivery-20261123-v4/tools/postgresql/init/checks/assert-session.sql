-- Local, pinned baseline. A changed endpoint/version requires another review.
DO $check$
BEGIN
    IF current_database() <> 'postgres'
       OR session_user <> 'postgres'
       OR current_user <> 'postgres'
       OR current_setting('server_version_num')::integer <> 180006
       OR inet_server_addr() IS DISTINCT FROM '127.0.0.1'::inet
       OR inet_server_port() IS DISTINCT FROM 5432 THEN
        RAISE EXCEPTION 'Expected postgres administrator, maintenance database, PostgreSQL 18.6 at 127.0.0.1:5432';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = current_user AND rolsuper) THEN
        RAISE EXCEPTION 'Reviewed administrator role must be a superuser';
    END IF;
    IF current_setting('password_encryption') <> 'scram-sha-256' THEN
        RAISE EXCEPTION 'Password encryption baseline changed; review required';
    END IF;
END;
$check$;
