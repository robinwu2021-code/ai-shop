-- Preserve all receipt invariants while naming the explicitly confirmed merchant-offline channel.
SET @sv_offline_constraint = (
    SELECT tc.CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS tc
    JOIN information_schema.CHECK_CONSTRAINTS cc
      ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME
    WHERE tc.CONSTRAINT_SCHEMA=DATABASE() AND tc.TABLE_NAME='sv_recharge'
      AND cc.CHECK_CLAUSE LIKE '%collection_method%' LIMIT 1
);
SET @sv_offline_drop = IF(@sv_offline_constraint IS NULL,'SELECT 1',
    CONCAT('ALTER TABLE sv_recharge DROP ',IF(LOCATE('MariaDB',VERSION())>0,'CONSTRAINT','CHECK'),' `',REPLACE(@sv_offline_constraint,'`','``'),'`'));
PREPARE sv_offline_stmt FROM @sv_offline_drop;
EXECUTE sv_offline_stmt;
DEALLOCATE PREPARE sv_offline_stmt;
ALTER TABLE sv_recharge ADD CONSTRAINT ck_sv_collection_receipt CHECK (
    status NOT IN ('SUBMITTED','RECEIVED','CREDITED') OR
    (collection_method IS NOT NULL AND actual_amount_minor IS NOT NULL
     AND collection_method IN ('CASH','TRANSFER','MERCHANT_OFFLINE')
     AND actual_amount_minor=principal_minor AND receipt_ref IS NOT NULL AND receipt_digest IS NOT NULL)
);
