-- STORE pools keep their existing store key; BRAND pools use the financial issuer key.
ALTER TABLE sv_issue_quota MODIFY COLUMN store_no VARCHAR(64) NULL;
ALTER TABLE sv_issue_quota ADD COLUMN issuer_no VARCHAR(64) NULL;
ALTER TABLE sv_issue_quota ADD UNIQUE KEY uk_sv_quota_issuer(tenant_no,issuer_no,currency);
ALTER TABLE sv_issue_quota ADD CONSTRAINT ck_sv_quota_scope CHECK ((store_no IS NOT NULL AND issuer_no IS NULL) OR (store_no IS NULL AND issuer_no IS NOT NULL));
