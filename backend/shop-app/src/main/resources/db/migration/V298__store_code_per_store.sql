-- 一店一码：店铺码从「一主体一码」下沉到门店。
--
-- 此前码挂在 mch_entity 上，多门店商家的每家分店贴的是同一个码 ——
-- 扫码数、进店数、注册数在分店之间分不开，而「哪家店的贴纸有用」正是
-- 商家想问的第一个问题。mkt_store_visit 早就留了 store_no 列，一直是空的。
--
-- **已经印出去贴在店里的码不作废。** 默认店直接继承主体上那个码（下面的回填），
-- 于是旧贴纸照常扫得进来，只是从此归到默认店名下 —— 这与今天的口径完全一致
-- （今天所有分店共用一个码，本来也只能算到主体头上）。
-- 想让分店各算各的，商家再给分店印新码即可，是增量动作，不是被迫更换。
--
-- mch_entity.store_code 保留不动：它是旧码的出处，且 uk_store_code 还在挡撞码。
-- 新码一律发到 mch_store.store_code；解析时先查门店表，查不到再回落主体表。

ALTER TABLE mch_store
    ADD COLUMN store_code VARCHAR(32) DEFAULT NULL COMMENT '这家店自己的店铺码。默认店回填自 mch_entity.store_code（旧贴纸因此不作废）；分店为空 = 还没发过码';

-- 唯一：码要能反查到唯一一家店，否则扫码落到哪家店取决于查询顺序
ALTER TABLE mch_store
    ADD UNIQUE KEY uk_mch_store_code (store_code);

-- 回填默认店。写成相关子查询而不是多表 UPDATE：后者是 MySQL/MariaDB 的方言，
-- 这条能在标准 SQL 下跑（数据库可移植性-MariaDB到MySQL §3）。
UPDATE mch_store s
SET s.store_code = (SELECT e.store_code
                    FROM mch_entity e
                    WHERE e.entity_no = s.entity_no)
WHERE s.is_default = 1
  AND s.store_code IS NULL
  AND EXISTS (SELECT 1
              FROM mch_entity e
              WHERE e.entity_no = s.entity_no
                AND e.store_code IS NOT NULL
                AND e.store_code <> '');

-- 每家店自己的小程序码图。
--
-- **不是省事的缓存，是额度**：wxacode.getUnlimited 是永久码且每个 appid 总量有限
-- （十万级）。码下沉到门店之后，每家分店都要单独一张 —— 不落库复用的话，
-- 商家反复刷新页面就能把额度耗掉，而额度用尽后新入驻的商家再也拿不到码。
ALTER TABLE mch_store
    ADD COLUMN acode_base64 MEDIUMTEXT NULL COMMENT '这家店的小程序码 PNG base64（不含 data: 前缀）。生成一次就复用 —— 微信永久码额度有限';

-- 印刷量也下沉到门店。
--
-- V292 建这张表时码还是一主体一码，所以印量只挂了主体。现在一店一码，
-- 「这家分店印了多少张」是运营真正要回答的问题 —— 挂主体的话，
-- 三家分店的印量糊成一个数，谁都不知道该催谁。
--
-- **回填到默认店**：历史登记发生在只有一个码的年代，那个码现在归默认店。
ALTER TABLE mch_store_qrcode_print
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '印的是哪家店的码。历史行回填为该主体的默认店';

UPDATE mch_store_qrcode_print p
SET p.store_no = (SELECT s.store_no
                  FROM mch_store s
                  WHERE s.entity_no = p.entity_no
                    AND s.is_default = 1)
WHERE p.store_no IS NULL
  AND EXISTS (SELECT 1
              FROM mch_store s
              WHERE s.entity_no = p.entity_no
                AND s.is_default = 1);
