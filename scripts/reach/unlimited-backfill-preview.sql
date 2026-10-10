-- V397 存量回填的**上线前对照量**（ADR-034）
--
-- 只读。在生产库上跑它得到一个数 N = 「此前靠隐式规则全平台可见、因此需要补 UNLIMITED 行」的门店数。
-- 迁移跑完之后：
--   SELECT COUNT(*) FROM mch_service_area WHERE created_by = 'V397_UNLIMITED_BACKFILL';
-- 这两个数**必须相等**。不等就说明迁移里那段判据与 StoreRoutes.of 的翻译对不上，
-- 而那种偏差没有任何报错 —— 症状是一批商家在上线当天从 C 端消失（或反过来，集体铺满全平台）。
--
-- 判据与 V397__backfill_unlimited_service_area.java / StoreRoutes.of 三处必须一致：
--   ① 一条 ACTIVE 纳入项都没有（「没框范围」）
--   ② 且有「不限落点」的路：enabled=1 且未被运营锁、且 scope_mode<>'SUBSET' 的 EXPRESS / MERCHANT_DELIVERY
--   ③ 一条可用的路都没有时，回落主体旧单值列 fulfillment_reach：
--      SHIPPING→算、PICKUP 或 NULL→不算（自提没有落点）、其余→算（自送）
--
-- 用法：
--   mysql -S /run/mysqld97/mysqld.sock ai_shop < scripts/reach/unlimited-backfill-preview.sql

SELECT COUNT(*) AS will_backfill
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no
WHERE s.status = 'ACTIVE' AND s.deleted = 0
  AND e.status = 'ACTIVE' AND e.deleted = 0
  AND s.store_no IS NOT NULL
  -- ① 没有生效的纳入项
  AND NOT EXISTS (
        SELECT 1 FROM mch_service_area a
        WHERE a.store_no = s.store_no AND a.mode = 'INCLUDE' AND a.status = 'ACTIVE' AND a.deleted = 0)
  -- 幂等：已经有 UNLIMITED 行的不算（重复跑时这个数会变小，属正常）
  AND NOT EXISTS (
        SELECT 1 FROM mch_service_area a
        WHERE a.store_no = s.store_no AND a.level = 'UNLIMITED' AND a.deleted = 0)
  AND (
        -- ② 有「全部」的快递/自送路
        EXISTS (
            SELECT 1 FROM mch_fulfillment_channel c
            WHERE c.store_no = s.store_no AND c.enabled = 1 AND c.deleted = 0
              AND (c.ops_locked IS NULL OR c.ops_locked = 0)
              AND (c.scope_mode IS NULL OR c.scope_mode <> 'SUBSET')
              AND c.channel IN ('EXPRESS', 'MERCHANT_DELIVERY'))
        -- ③ 一条可用的路都没有 → 回落旧单值列
     OR (
            NOT EXISTS (
                SELECT 1 FROM mch_fulfillment_channel c
                WHERE c.store_no = s.store_no AND c.enabled = 1 AND c.deleted = 0
                  AND (c.ops_locked IS NULL OR c.ops_locked = 0))
            AND COALESCE(e.fulfillment_reach, 'PICKUP') <> 'PICKUP')
  );

-- 明细（核对抽样用）：按主体列出待回填的门店与它凭哪一条成立
SELECT s.entity_no, e.name AS entity_name, s.store_no, s.name AS store_name,
       e.fulfillment_reach AS legacy_reach,
       (SELECT GROUP_CONCAT(CONCAT(c.channel, ':', COALESCE(c.scope_mode, 'ALL')))
        FROM mch_fulfillment_channel c
        WHERE c.store_no = s.store_no AND c.enabled = 1 AND c.deleted = 0
          AND (c.ops_locked IS NULL OR c.ops_locked = 0)) AS live_routes
FROM mch_store s
JOIN mch_entity e ON e.entity_no = s.entity_no
WHERE s.status = 'ACTIVE' AND s.deleted = 0
  AND e.status = 'ACTIVE' AND e.deleted = 0
  AND s.store_no IS NOT NULL
  AND NOT EXISTS (
        SELECT 1 FROM mch_service_area a
        WHERE a.store_no = s.store_no AND a.mode = 'INCLUDE' AND a.status = 'ACTIVE' AND a.deleted = 0)
  AND NOT EXISTS (
        SELECT 1 FROM mch_service_area a
        WHERE a.store_no = s.store_no AND a.level = 'UNLIMITED' AND a.deleted = 0)
  AND (
        EXISTS (
            SELECT 1 FROM mch_fulfillment_channel c
            WHERE c.store_no = s.store_no AND c.enabled = 1 AND c.deleted = 0
              AND (c.ops_locked IS NULL OR c.ops_locked = 0)
              AND (c.scope_mode IS NULL OR c.scope_mode <> 'SUBSET')
              AND c.channel IN ('EXPRESS', 'MERCHANT_DELIVERY'))
     OR (
            NOT EXISTS (
                SELECT 1 FROM mch_fulfillment_channel c
                WHERE c.store_no = s.store_no AND c.enabled = 1 AND c.deleted = 0
                  AND (c.ops_locked IS NULL OR c.ops_locked = 0))
            AND COALESCE(e.fulfillment_reach, 'PICKUP') <> 'PICKUP')
  )
ORDER BY s.entity_no, s.store_no;
