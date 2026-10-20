-- Retain V1 evidence/ciphertext. Manifest V2 is a separate protocol and cannot promote an old record.
ALTER TABLE ent_migration_stage
 ADD COLUMN manifest_version INT NOT NULL DEFAULT 1,
 ADD COLUMN history_available TINYINT NULL DEFAULT NULL;
UPDATE ent_migration_stage SET history_available=CASE WHEN source_system='PMT' THEN 1 ELSE 0 END;
ALTER TABLE ent_migration_stage
 ADD CONSTRAINT ck_migration_manifest_version CHECK (manifest_version IN (1,2)),
 ADD CONSTRAINT ck_migration_manifest_history CHECK (
   (manifest_version=1 AND history_available IS NULL)
   OR (history_available IS NOT NULL AND
       ((source_system='PMT' AND history_available=1) OR (source_system='MKT_LEGACY' AND history_available=0))));
