-- Bind an idempotent checkout response to its validated input; preserve legacy NULL hashes and the existing key scope.
ALTER TABLE sys_idempotent ADD COLUMN request_hash VARCHAR(64) NULL;
