ALTER TABLE sys_idempotent ADD COLUMN request_hash VARCHAR(64);
CREATE TABLE core_migration_probe(id BIGINT PRIMARY KEY);
