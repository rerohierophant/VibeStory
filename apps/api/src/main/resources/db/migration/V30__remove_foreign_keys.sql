-- Relationships are checked by the API. Keep IDs, indexes and all other constraints.
DO $$
DECLARE
    fk record;
BEGIN
    FOR fk IN
        SELECT n.nspname AS schema_name, t.relname AS table_name, c.conname AS constraint_name
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE c.contype = 'f' AND n.nspname = current_schema()
        ORDER BY t.relname, c.conname
    LOOP
        EXECUTE format('ALTER TABLE %I.%I DROP CONSTRAINT %I', fk.schema_name, fk.table_name, fk.constraint_name);
    END LOOP;
END $$;
