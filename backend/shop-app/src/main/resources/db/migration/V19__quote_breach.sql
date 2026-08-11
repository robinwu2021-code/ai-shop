-- 报价毁约状态（P-8.2.5）。
--
-- 只改列注释，不改结构：status 是 VARCHAR(16)，多一个取值不需要 DDL。
-- 但注释必须跟上 —— 注释写着「ACTIVE/WITHDRAWN」而库里真的会出现 BREACH，
-- 是比没有注释更糟的情况：看的人会以为自己看到了全集。
ALTER TABLE mkt_quote
    MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        COMMENT 'ACTIVE/WITHDRAWN/BREACH。BREACH 由平台判定，同时写一条 mch_violation';
