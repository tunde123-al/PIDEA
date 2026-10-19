DO $migration$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pidea_app') THEN
        EXECUTE 'REVOKE USAGE ON SCHEMA analytics FROM pidea_app';
        EXECUTE 'REVOKE SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA analytics FROM pidea_app';
        EXECUTE format(
            'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA analytics '
            'REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM pidea_app',
            current_user
        );
    END IF;
END
$migration$;
