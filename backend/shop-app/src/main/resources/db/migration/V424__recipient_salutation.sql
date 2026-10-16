-- 可选称呼：存量保留 NULL，不回填推断值；旧应用仍可读写其余字段。
ALTER TABLE usr_address ADD COLUMN salutation VARCHAR(16) DEFAULT NULL COMMENT 'MR/MS/UNSPECIFIED';
ALTER TABLE ord_sub_order ADD COLUMN receiver_salutation VARCHAR(16) DEFAULT NULL COMMENT '收件人称呼快照';
