-- Version used by unified order pricing. Legacy uses without a proven version stay NULL.
ALTER TABLE pmt_apply
    ADD COLUMN activity_version_no VARCHAR(64) NULL;
