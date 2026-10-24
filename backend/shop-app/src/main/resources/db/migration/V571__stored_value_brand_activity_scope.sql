-- Brand operational ownership is separate from the settlement entity retained by the activity engine.
CREATE TABLE pmt_brand_activity_owner (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL,
 activity_no VARCHAR(64) NOT NULL,
 management_no VARCHAR(64) NOT NULL,
 brand_no VARCHAR(64) NOT NULL,
 entity_no VARCHAR(64) NOT NULL,
 created_at DATETIME(3) NOT NULL,
 created_by VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_pmt_brand_activity(tenant_no,activity_no),
 KEY ix_pmt_brand_activity_owner(tenant_no,management_no,brand_no,activity_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci;
ALTER TABLE pmt_sv_release_link MODIFY COLUMN store_no VARCHAR(64) NULL;
ALTER TABLE pmt_sv_release_link ADD COLUMN scope_type VARCHAR(24) NOT NULL DEFAULT 'STORE';
ALTER TABLE pmt_sv_release_link ADD COLUMN scope_no VARCHAR(64) NULL;
UPDATE pmt_sv_release_link SET scope_no=store_no WHERE scope_type='STORE';
ALTER TABLE pmt_sv_approval MODIFY COLUMN store_no VARCHAR(64) NULL;
ALTER TABLE pmt_sv_approval ADD COLUMN scope_type VARCHAR(24) NOT NULL DEFAULT 'STORE';
ALTER TABLE pmt_sv_approval ADD COLUMN scope_no VARCHAR(64) NULL;
UPDATE pmt_sv_approval SET scope_no=store_no WHERE scope_type='STORE';
