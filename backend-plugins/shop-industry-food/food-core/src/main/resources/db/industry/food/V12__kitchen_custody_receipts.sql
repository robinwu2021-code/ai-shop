-- Physical packing and custody evidence does not imply original core delivery or inventory issue.
ALTER TABLE `fnb_service_event`
    ADD COLUMN `custody_snapshot` TEXT NULL COMMENT 'Immutable packing/custody receipt with original line and recipient scope';
