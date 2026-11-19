-- UME 同步对账（只读）：每个 UME 商户一行，逐层核对 UME 原数据 → ai-shop → near-pay，stage 给出第一处缺口。
-- 运行：在 db61 上以有两库读权限的账号执行，输出 TSV。只含编号、计数与状态，不含个人资料。
SELECT x.source_merchant_id, x.entity_no, x.entity_status, x.owner_ok, x.src_stores, x.shop_stores, x.proj_stores,
       x.linked_stores, x.coded_stores, x.pay_merchant_no, x.src_channels, x.mappable_channels, x.accounts, x.active_accounts,
       CASE WHEN x.entity_no IS NULL THEN '1_NO_ENTITY'
            WHEN x.owner_ok = 0 THEN '2_NO_OWNER'
            WHEN x.shop_stores < x.src_stores THEN '3_STORES_MISSING'
            WHEN x.proj_stores < x.shop_stores THEN '4_STORES_NOT_SYNCED'
            WHEN x.pay_merchant_no IS NULL THEN '5_NO_PAY_MERCHANT'
            WHEN x.linked_stores < x.proj_stores THEN '6_STORES_NOT_LINKED'
            WHEN x.accounts < x.src_channels THEN '7_CHANNELS_MISSING'
            WHEN x.coded_stores < x.shop_stores THEN '8_STORE_CODES_MISSING'
            ELSE '9_SYNCED' END AS stage
FROM (
  SELECT m.merchant_id AS source_merchant_id,
         e.entity_no, e.status AS entity_status,
         (e.owner_user_no IS NOT NULL AND EXISTS (SELECT 1 FROM ai_shop_ume.mch_account a WHERE a.entity_no = e.entity_no
              AND a.user_no = e.owner_user_no AND a.is_owner = 1 AND a.status = 'ACTIVE' AND a.deleted = 0)) AS owner_ok,
         (SELECT COUNT(*) FROM ai_shop_ume.stg_ume_active_stores_20261113 s WHERE s.merchant_id = m.merchant_id COLLATE utf8mb4_general_ci) AS src_stores,
         (SELECT COUNT(*) FROM ai_shop_ume.mch_store st WHERE st.entity_no = e.entity_no AND st.deleted = 0 AND st.store_no LIKE 'STUME%') AS shop_stores,
         (SELECT COUNT(*) FROM nearpay_prod.pay_store_projection p WHERE p.external_entity_id = e.entity_no COLLATE utf8mb4_0900_ai_ci AND p.store_no LIKE 'STUME%') AS proj_stores,
         (SELECT COUNT(DISTINCT p.id) FROM nearpay_prod.pay_store_projection p JOIN nearpay_prod.pay_merchant_store_link sl
              ON sl.projection_id = p.id AND sl.valid_to IS NULL
            WHERE p.external_entity_id = e.entity_no COLLATE utf8mb4_0900_ai_ci AND p.store_no LIKE 'STUME%') AS linked_stores,
         (SELECT COUNT(*) FROM ai_shop_ume.mch_store st WHERE st.entity_no = e.entity_no AND st.deleted = 0 AND st.store_no LIKE 'STUME%'
              AND st.slug IS NOT NULL) AS coded_stores,
         (SELECT pm.merchant_no FROM nearpay_prod.pay_merchant_source_link l JOIN nearpay_prod.pay_platform_merchant pm ON pm.id = l.platform_merchant_id
            WHERE l.external_entity_id = e.entity_no COLLATE utf8mb4_0900_ai_ci AND l.verify_status = 'VERIFIED' LIMIT 1) AS pay_merchant_no,
         (SELECT COUNT(*) FROM nearpay_prod.mig_ume_channel_20261117 c WHERE c.source_merchant_id = m.merchant_id) AS src_channels,
         (SELECT COUNT(*) FROM nearpay_prod.mig_ume_channel_20261117 c WHERE c.source_merchant_id = m.merchant_id AND c.target_channel IS NOT NULL) AS mappable_channels,
         (SELECT COUNT(*) FROM nearpay_prod.pay_merchant_source_link l JOIN nearpay_prod.pay_merchant a ON a.platform_merchant_id = l.platform_merchant_id
            WHERE l.external_entity_id = e.entity_no COLLATE utf8mb4_0900_ai_ci AND l.verify_status = 'VERIFIED' AND a.owner_type = 'MERCHANT'
              AND a.deleted = 0 AND a.status <> 'CLOSED' AND a.pay_channel NOT LIKE '%\_TEST') AS accounts,
         (SELECT COUNT(*) FROM nearpay_prod.pay_merchant_source_link l JOIN nearpay_prod.pay_merchant a ON a.platform_merchant_id = l.platform_merchant_id
            WHERE l.external_entity_id = e.entity_no COLLATE utf8mb4_0900_ai_ci AND l.verify_status = 'VERIFIED' AND a.owner_type = 'MERCHANT'
              AND a.deleted = 0 AND a.status = 'ACTIVE' AND a.pay_channel NOT LIKE '%\_TEST') AS active_accounts
  FROM nearpay_prod.stg_ume_active_payment_merchants_20261112 m
  LEFT JOIN ai_shop_ume.mch_entity e ON e.entity_no = CONCAT('MUME', m.merchant_id) COLLATE utf8mb4_general_ci AND e.deleted = 0
) x
ORDER BY stage, x.source_merchant_id;
