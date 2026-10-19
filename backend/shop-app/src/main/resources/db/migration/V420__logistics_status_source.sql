ALTER TABLE lgs_waybill
    ADD COLUMN status_source VARCHAR(32) NULL COMMENT 'Last channel that advanced status or added a trace node';
