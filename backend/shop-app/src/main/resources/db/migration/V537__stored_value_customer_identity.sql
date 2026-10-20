-- A customer can exist before a login account. Historical user identity and monetary facts remain intact.
ALTER TABLE sv_account MODIFY user_no VARCHAR(64) NULL, ADD customer_no VARCHAR(64) NULL;
UPDATE sv_account SET customer_no=user_no WHERE customer_no IS NULL;
ALTER TABLE sv_account ADD UNIQUE KEY uk_sv_customer_owner(tenant_no,customer_no,entity_no,store_no,currency);
ALTER TABLE sv_recharge MODIFY user_no VARCHAR(64) NULL, ADD customer_no VARCHAR(64) NULL;
UPDATE sv_recharge SET customer_no=user_no WHERE customer_no IS NULL;
ALTER TABLE sv_statement MODIFY user_no VARCHAR(64) NULL, ADD customer_no VARCHAR(64) NULL;
UPDATE sv_statement SET customer_no=user_no WHERE customer_no IS NULL;
ALTER TABLE sv_user_store_index MODIFY user_no VARCHAR(64) NULL, ADD customer_no VARCHAR(64) NULL;
UPDATE sv_user_store_index SET customer_no=user_no WHERE customer_no IS NULL;
ALTER TABLE sv_user_store_index ADD UNIQUE KEY uk_sv_customer_store(tenant_no,customer_no,store_no);
ALTER TABLE mbr_identity_ticket MODIFY user_no VARCHAR(64) NULL, ADD customer_no VARCHAR(64) NULL;
UPDATE mbr_identity_ticket SET customer_no=user_no WHERE customer_no IS NULL;
CREATE TABLE sv_customer_identity (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 tenant_no VARCHAR(32) NOT NULL,
 customer_no VARCHAR(64) NOT NULL,
 user_no VARCHAR(64) NOT NULL,
 UNIQUE KEY uk_sv_customer_binding(tenant_no,customer_no),
 UNIQUE KEY uk_sv_user_binding(tenant_no,user_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信人档与已验证登录账号绑定，不存资金';

-- Member selection is its own provenance; it never fabricates a source membership-code hash.
SET @sv_selection_constraint = (
 SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
 JOIN information_schema.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
 WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='mbr_identity_ticket'
 AND cc.CHECK_CLAUSE LIKE '%purpose%' AND cc.CHECK_CLAUSE LIKE '%source_ticket_hash%' LIMIT 1
);
SET @sv_selection_drop=IF(@sv_selection_constraint IS NULL,'SELECT 1',CONCAT('ALTER TABLE mbr_identity_ticket DROP ',IF(LOCATE('MariaDB',VERSION())>0,'CONSTRAINT','CHECK'),' `',REPLACE(@sv_selection_constraint,'`','``'),'`'));
PREPARE sv_selection_stmt FROM @sv_selection_drop;
EXECUTE sv_selection_stmt;
DEALLOCATE PREPARE sv_selection_stmt;
ALTER TABLE mbr_identity_ticket ADD CONSTRAINT ck_mbr_identification_purpose CHECK (
 (purpose='MEMBER_CODE' AND user_no IS NOT NULL AND entity_no IS NULL AND store_no IS NULL AND actor_no IS NULL AND source_ticket_hash IS NULL)
 OR (purpose='CUSTOMER_REFERENCE' AND user_no IS NOT NULL AND entity_no IS NOT NULL AND store_no IS NOT NULL AND actor_no IS NOT NULL AND source_ticket_hash IS NOT NULL)
 OR (purpose='CUSTOMER_SELECTION' AND customer_no IS NOT NULL AND entity_no IS NOT NULL AND store_no IS NOT NULL AND actor_no IS NOT NULL AND source_ticket_hash IS NULL)
);
