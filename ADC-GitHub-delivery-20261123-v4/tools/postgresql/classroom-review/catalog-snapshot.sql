BEGIN READ ONLY;
SELECT jsonb_build_object(
    'runtimeRole', 'p0_t028_runtime',
    'tables', (SELECT jsonb_agg(jsonb_build_object(
        'name', t.relname,
        'owner', pg_get_userbyid(t.relowner),
        'acl', t.relacl,
        'runtime', jsonb_build_object(
            'select', has_table_privilege('p0_t028_runtime', t.oid, 'SELECT'),
            'insert', has_table_privilege('p0_t028_runtime', t.oid, 'INSERT'),
            'update', has_table_privilege('p0_t028_runtime', t.oid, 'UPDATE'),
            'delete', has_table_privilege('p0_t028_runtime', t.oid, 'DELETE')),
        'columns', (SELECT jsonb_agg(jsonb_build_object(
            'name', a.attname, 'type', format_type(a.atttypid, a.atttypmod), 'notNull', a.attnotnull,
            'default', pg_get_expr(d.adbin, d.adrelid), 'acl', a.attacl,
            'runtimeSelect', has_column_privilege('p0_t028_runtime', t.oid, a.attnum, 'SELECT'),
            'runtimeInsert', has_column_privilege('p0_t028_runtime', t.oid, a.attnum, 'INSERT'),
            'runtimeUpdate', has_column_privilege('p0_t028_runtime', t.oid, a.attnum, 'UPDATE'))
            ORDER BY a.attnum)
            FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
            WHERE a.attrelid=t.oid AND a.attnum>0 AND NOT a.attisdropped),
        'constraints', (SELECT COALESCE(jsonb_agg(jsonb_build_object(
            'name', k.conname, 'type', k.contype, 'validated', k.convalidated,
            'definition', pg_get_constraintdef(k.oid)) ORDER BY k.conname), '[]'::jsonb)
            FROM pg_constraint k WHERE k.conrelid=t.oid),
        'indexes', (SELECT COALESCE(jsonb_agg(pg_get_indexdef(i.indexrelid) ORDER BY i.indexrelid), '[]'::jsonb)
            FROM pg_index i WHERE i.indrelid=t.oid)
    ) ORDER BY n.nspname, t.relname)
    FROM pg_class t JOIN pg_namespace n ON n.oid=t.relnamespace
    WHERE n.nspname='adc' AND t.relkind IN ('r','p'))
);
COMMIT;
