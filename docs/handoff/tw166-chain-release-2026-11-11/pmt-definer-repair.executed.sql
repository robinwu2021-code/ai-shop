-- 166 environment repair executed 2026-11-11; not a Flyway migration.
-- Before execution: account absent; 18 unchanged pmt triggers used this definer.
-- The account is locked and cannot log in. Grants cover six pmt tables and their fence only.
CREATE USER 'shop'@'127.0.0.1' ACCOUNT LOCK;
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_activity` TO 'shop'@'127.0.0.1';
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_activity_goods` TO 'shop'@'127.0.0.1';
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_activity_rule` TO 'shop'@'127.0.0.1';
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_activity_version` TO 'shop'@'127.0.0.1';
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_enrollment` TO 'shop'@'127.0.0.1';
GRANT SELECT, TRIGGER ON ai_shop_ume.`pmt_enrollment_goods` TO 'shop'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE ON ai_shop_ume.mkt_counter_campaign_fence TO 'shop'@'127.0.0.1';
