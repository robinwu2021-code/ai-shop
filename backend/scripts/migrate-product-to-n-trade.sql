-- 商品领域：旧库 → 门店交易库 n_trade_v2 + 会员营销库 n_member（TDD-商品领域迁到门店交易库 §2.5，
-- 取值约定见 docs/technical/design/商品领域-新库改动清单.md §3）。
--
-- 不直接执行本文件：由 scripts/migrate-product-to-n-trade.py 把 {src} {trade} {member} 换成库名后交给 mysql 批处理客户端。
-- 一个事务；任何一条检查不过（stock_guard 的 CHECK）或任何一条 SQL 出错，整批回滚。不要用 --force。
-- 要求目标表为空：只搬一次。重搬先重建目标库（或清空这些表）。
--
-- 不在本文件：评价三张（暂停）、库存同步一组（暂停，表退役）、门店经营分类 mch_store_category
-- （用 deploy/ume/product-cutover/reconcile_store_categories.py，行编号规则在商家域）。
USE {trade};
SET @home_market = 'TW';
SET @legacy_market = 'CN';

CREATE TEMPORARY TABLE migrate_guard (
    check_name VARCHAR(64) NOT NULL,
    problem_count BIGINT NOT NULL,
    CHECK (problem_count = 0)
);

START TRANSACTION;

-- ---------------------------------------------------------------- 0 目标必须为空
INSERT INTO migrate_guard
SELECT 'target_not_empty', (SELECT COUNT(*) FROM {trade}.prd_category) + (SELECT COUNT(*) FROM {trade}.prd_goods)
     + (SELECT COUNT(*) FROM {trade}.prd_sku) + (SELECT COUNT(*) FROM {trade}.prd_spec_usage)
     + (SELECT COUNT(*) FROM {trade}.prd_store_sku) + (SELECT COUNT(*) FROM {trade}.prd_goods_revision)
     + (SELECT COUNT(*) FROM {member}.prd_topic) + (SELECT COUNT(*) FROM {member}.mbr_favorite WHERE favorite_type = 'GOODS');

-- 同一个 SKU 既有 CN 又有 TW 行：市场改写后撞 (entity_no, sku_no, market)，先人工合并
INSERT INTO migrate_guard
SELECT 'sku_market_collision', COUNT(*) FROM (
    SELECT entity_no, sku_no FROM {src}.prd_sku
    WHERE market IN (@legacy_market, @home_market)
    GROUP BY entity_no, sku_no HAVING COUNT(DISTINCT market) > 1) x;

-- ---------------------------------------------------------------- 1 类目（并入付款方式：只记禁止的）
INSERT INTO {trade}.prd_category
    (tenant_no, category_no, parent_no, level, name, name_en, icon, sort, template, attr_template,
     qualification_required, required_code, disallowed_pay_modes, status, deleted, version,
     created_at, created_by, updated_at, updated_by)
SELECT c.tenant_no, c.category_no, c.parent_no, c.level, c.name, c.name_en, c.icon, c.sort, c.template, c.attr_template,
       c.qualification_required, c.required_code,
       (SELECT CONCAT('[', GROUP_CONCAT(CONCAT('"', p.pay_mode, '"') ORDER BY p.pay_mode SEPARATOR ','), ']')
          FROM {src}.prd_category_pay_mode p
         WHERE p.category_no = c.category_no AND p.allowed = 0 AND p.deleted = 0),
       c.status, c.deleted, c.version, c.created_at, c.created_by, c.updated_at, c.updated_by
FROM {src}.prd_category c;

-- ---------------------------------------------------------------- 2 商品、标准品、可售规则
INSERT INTO {trade}.prd_goods
    (tenant_no, goods_no, entity_no, store_no, store_name, std_no, title, title_i18n, subtitle, subtitle_i18n,
     cover, images, detail, detail_images, type, category_no, fulfillments, spec_groups, params,
     rating, rating_count, sales, limit_per_user, on_sale, pending_on_sale, sale_mode, audit_status, audit_reason,
     cutoff_at, arrival_desc, weighed, origin, duration_min, points_config, group_price_minor, group_min_count,
     sellable_override, pay_modes, inv_mode, new_notified_at, restricted_regions, freight_template_no,
     deleted, version, created_at, created_by, updated_at, updated_by)
-- 商品一定有所属门店（读路径只认这一列；商品与库存方案 §3）：旧商品没填的取主体默认店
SELECT tenant_no, goods_no, entity_no,
       COALESCE(NULLIF(store_no, ''), (SELECT MIN(ms.store_no) FROM {src}.mch_store ms
                                        WHERE ms.entity_no = prd_goods.entity_no AND ms.deleted = 0 AND ms.is_default = 1)),
       store_name, std_no, title, title_i18n, subtitle, subtitle_i18n,
       cover, images, detail, detail_images, type, category_no, fulfillments, spec_groups, params,
       rating, rating_count, sales, limit_per_user, on_sale, pending_on_sale, sale_mode, audit_status, audit_reason,
       cutoff_at, arrival_desc, weighed, origin, duration_min, points_config, group_price_minor, group_min_count,
       sellable_override, pay_modes, inv_mode, new_notified_at, restricted_regions, freight_template_no,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_goods;

INSERT INTO {trade}.prd_standard_goods
    (tenant_no, std_no, barcode, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords,
     source, ref_count, status, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, std_no, barcode, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords,
       source, ref_count, status, deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_spu_std;

INSERT INTO {trade}.prd_sellable_rule
    (tenant_no, store_no, scope_type, scope_ref, rule_type, param, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, store_no, scope_type, scope_ref, rule_type, param, deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_sell_rule;

-- ---------------------------------------------------------------- 3 SKU（并入定价策略、卡权益绑定；CN → TW；库存不在这里）
INSERT INTO {trade}.prd_sku
    (tenant_no, sku_no, goods_no, entity_no, market, option_values, option_value_nos, spec, price, origin_price,
     cost_price, tax_class, promotion_eligible, nominal_gram, sale_unit, barcode, merchant_sku_code,
     presale_quota, sold_count, cutoff_at, arrive_at,
     entitlement_version_no, entitlement_rule_snapshot, entitlement_units, status,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT s.tenant_no, s.sku_no, s.goods_no, s.entity_no,
       IF(s.market = @legacy_market, @home_market, s.market),
       s.option_values, s.option_value_nos, s.spec, s.price, s.origin_price,
       s.cost_price, p.tax_class, COALESCE(p.promotion_eligible, 1), s.nominal_gram, s.sale_unit, s.barcode, s.merchant_sku_code,
       COALESCE(s.presale_quota, 0), COALESCE(s.sold_count, 0), s.cutoff_at, s.arrive_at,
       b.template_version_no, b.rule_snapshot, COALESCE(b.units_per_item, 0),
       IF(b.id IS NOT NULL AND (b.deleted <> 0 OR b.status <> 'ACTIVE'), 'DISABLED', 'ACTIVE'),
       s.deleted, s.version, s.created_at, s.created_by, s.updated_at, s.updated_by
FROM {src}.prd_sku s
LEFT JOIN {src}.prd_sku_pricing_policy p
       ON p.sku_no = s.sku_no AND p.deleted = 0 AND s.market IN (@legacy_market, @home_market)
LEFT JOIN {src}.prd_entitlement_binding b
       ON b.sku_no = s.sku_no AND b.market = s.market;

-- ---------------------------------------------------------------- 4 规格：维度、取值、使用范围
INSERT INTO {trade}.prd_spec_dim
    (tenant_no, dim_no, code, name, name_i18n, value_type, unit, usage_type, universal, scope, entity_no, sort, status,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, dim_no, code, name, name_i18n, value_type, unit, usage_type, universal, scope, entity_no, sort, status,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_spec_dim;

INSERT INTO {trade}.prd_spec_value
    (tenant_no, value_no, dim_no, code, label, label_i18n, numeric_value, numeric_unit, aliases, scope, entity_no, sort,
     status, merged_into, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, value_no, dim_no, code, label, label_i18n, numeric_value, numeric_unit, aliases, scope, entity_no, sort,
       status, merged_into, deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_spec_value;

-- 类目绑定（维度级行 value_no 为空串）
INSERT INTO {trade}.prd_spec_usage
    (tenant_no, scope_type, scope_ref, dim_no, usage_type, is_primary, required, sort, status, value_no,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, 'CATEGORY', category_no, dim_no, usage_type, is_primary, required, sort, status, '',
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_category_spec WHERE deleted = 0;

-- 类目取值子集
INSERT INTO {trade}.prd_spec_usage
    (tenant_no, scope_type, scope_ref, dim_no, sort, status, value_no, label_override,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, 'CATEGORY', category_no, dim_no, sort, 'ACTIVE', value_no, label_override,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_category_spec_value WHERE deleted = 0;

-- 商家覆盖：scope_ref = 商家号/类目号；enabled = 0 → DISABLED；取值级 value_no 照旧存 code
INSERT INTO {trade}.prd_spec_usage
    (tenant_no, scope_type, scope_ref, entity_no, dim_no, sort, status, value_no, label_override,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, 'MCH_CATEGORY', CONCAT(merchant_no, '/', category_no), merchant_no, dim_no, sort,
       IF(enabled = 0, 'DISABLED', 'ACTIVE'), COALESCE(value_no, ''), label_override,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_merchant_spec_override WHERE deleted = 0;

-- 商家常用维度与取值
INSERT INTO {trade}.prd_spec_usage
    (tenant_no, scope_type, scope_ref, entity_no, dim_no, sort, status, value_no, favorite,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, 'MERCHANT', entity_no, entity_no, dim_no, sort, 'ACTIVE', '', 1,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_merchant_spec WHERE deleted = 0;
INSERT INTO {trade}.prd_spec_usage
    (tenant_no, scope_type, scope_ref, entity_no, dim_no, sort, status, value_no, favorite,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, 'MERCHANT', entity_no, entity_no, dim_no, sort, 'ACTIVE', value_no, 1,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_merchant_spec_value WHERE deleted = 0;

-- 平台模板的品类兜底 → 品类推荐：按名字对到规格库的平台维度、选项按 code 对到取值；对不上的不搬（见报告）
CREATE TEMPORARY TABLE tpl_option AS
SELECT t.template_no, COALESCE(t.category_type, '*') AS scope_ref, t.name, t.status AS tpl_status, t.tenant_no,
       o.ord, o.code, o.label
FROM {src}.prd_spec_template t,
     JSON_TABLE(t.options, '$[*]' COLUMNS (ord FOR ORDINALITY,
                                           code VARCHAR(64) PATH '$.code',
                                           label VARCHAR(128) PATH '$.label')) o
WHERE t.deleted = 0 AND t.scope = 'PLATFORM' AND t.category_no IS NULL;

INSERT INTO {trade}.prd_spec_usage (tenant_no, scope_type, scope_ref, dim_no, sort, status, value_no)
SELECT MIN(x.tenant_no), 'CATEGORY_TYPE', x.scope_ref, d.dim_no, 0,
       IF(MAX(x.tpl_status = 'ACTIVE'), 'ACTIVE', 'DISABLED'), ''
FROM tpl_option x
JOIN {trade}.prd_spec_dim d ON d.scope = 'PLATFORM' AND d.name = x.name AND d.deleted = 0
GROUP BY x.scope_ref, d.dim_no;

INSERT INTO {trade}.prd_spec_usage (tenant_no, scope_type, scope_ref, dim_no, sort, status, value_no, label_override)
SELECT MIN(x.tenant_no), 'CATEGORY_TYPE', x.scope_ref, d.dim_no, MIN(x.ord) * 10, 'ACTIVE', v.value_no,
       IF(MIN(x.label) = MIN(v.label), NULL, MIN(x.label))
FROM tpl_option x
JOIN {trade}.prd_spec_dim d ON d.scope = 'PLATFORM' AND d.name = x.name AND d.deleted = 0
JOIN {trade}.prd_spec_value v ON v.dim_no = d.dim_no AND v.code = x.code AND v.scope = 'PLATFORM' AND v.deleted = 0
GROUP BY x.scope_ref, d.dim_no, v.value_no;

-- ---------------------------------------------------------------- 5 商品草稿 → 提交历史
INSERT INTO {trade}.prd_goods_revision
    (tenant_no, goods_no, entity_no, revision_no, base_revision, payload, change_summary, entry_source, status,
     reject_reason, published_by, published_at, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT tenant_no, goods_no, entity_no, revision_no, base_revision, payload, change_summary, entry_source, status,
       reject_reason, published_by, published_at, deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_goods_revision;

-- 草稿与最新一条未发布（DRAFT）内容相同：就是同一笔，改它的状态
UPDATE {trade}.prd_goods_revision r
JOIN {src}.prd_goods_draft d ON d.goods_no = r.goods_no AND d.deleted = 0 AND d.payload = r.payload
SET r.status = d.status, r.base_goods_version = d.base_version
WHERE r.status = 'DRAFT'
  AND r.revision_no = (SELECT mx FROM (SELECT goods_no, MAX(revision_no) mx FROM {trade}.prd_goods_revision
                                       GROUP BY goods_no) m WHERE m.goods_no = r.goods_no);

-- 其余草稿新开一行：编号接在该商品最大编号之后，基于当前在售那一版
INSERT INTO {trade}.prd_goods_revision
    (tenant_no, goods_no, entity_no, revision_no, base_revision, base_goods_version, payload, entry_source, status,
     deleted, created_at, created_by, updated_at, updated_by)
SELECT d.tenant_no, d.goods_no, d.entity_no,
       COALESCE((SELECT MAX(r.revision_no) FROM {src}.prd_goods_revision r WHERE r.goods_no = d.goods_no), 0) + 1,
       (SELECT MAX(r.revision_no) FROM {src}.prd_goods_revision r WHERE r.goods_no = d.goods_no AND r.status = 'ONLINE'),
       d.base_version, d.payload, 'MANUAL', d.status, 0, d.created_at, d.created_by, d.updated_at, d.updated_by
FROM {src}.prd_goods_draft d
WHERE d.deleted = 0 AND NOT EXISTS (
    SELECT 1 FROM {trade}.prd_goods_revision r WHERE r.goods_no = d.goods_no AND r.status IN ('EDITING', 'SUBMITTED'));

-- ---------------------------------------------------------------- 6 门店 SKU：门店价 + 店级库存 + 主体级库存池
CREATE TEMPORARY TABLE store_sku_in (
    tenant_no VARCHAR(32), entity_no VARCHAR(32), store_no VARCHAR(32), sku_no VARCHAR(32), market VARCHAR(8),
    price BIGINT, origin_price BIGINT, sellable INT, locked INT, has_price TINYINT, has_stock TINYINT
);
INSERT INTO store_sku_in
SELECT tenant_no, entity_no, store_no, sku_no, IF(market = @legacy_market, @home_market, market),
       price, origin_price, NULL, NULL, 1, 0
FROM {src}.prd_store_price WHERE deleted = 0;
INSERT INTO store_sku_in
SELECT tenant_no, entity_no, store_no, sku_no, @home_market, NULL, NULL, stock, locked_stock, 0, 1
FROM {src}.prd_store_stock WHERE deleted = 0;
-- 主体级库存（原 prd_sku.stock）的去处（商品与库存方案 §3.2 S5）：
--   单店主体（只有一家在用的店）且那家店没有这个 SKU 的店级库存 → 并进那家店的行（同一份货，行为不变）；
--   其余（多店主体，或那家店已有店级库存）→ 门店号为空串的库存池，与运行时口径一致（PR #59）。一个 SKU 一份。
CREATE TEMPORARY TABLE single_store (entity_no VARCHAR(32) PRIMARY KEY, store_no VARCHAR(32));
INSERT INTO single_store
SELECT entity_no, MIN(store_no) FROM {src}.mch_store WHERE deleted = 0 GROUP BY entity_no HAVING COUNT(*) = 1;
CREATE TEMPORARY TABLE pool_home (sku_no VARCHAR(32) PRIMARY KEY, store_no VARCHAR(32));
INSERT INTO pool_home
SELECT p.sku_no, MIN(IF(ss.store_no IS NOT NULL AND NOT EXISTS (
           SELECT 1 FROM {src}.prd_store_stock st
            WHERE st.deleted = 0 AND st.store_no = ss.store_no AND st.sku_no = p.sku_no), ss.store_no, ''))
FROM {src}.prd_sku p LEFT JOIN single_store ss ON ss.entity_no = p.entity_no
WHERE p.deleted = 0 AND p.stock IS NOT NULL
GROUP BY p.sku_no;
INSERT INTO store_sku_in
SELECT MIN(p.tenant_no), p.entity_no, h.store_no, p.sku_no, @home_market, NULL, NULL, MIN(p.stock), MIN(p.locked_stock), 0, 1
FROM {src}.prd_sku p JOIN pool_home h ON h.sku_no = p.sku_no
WHERE p.deleted = 0 AND p.stock IS NOT NULL
GROUP BY p.entity_no, p.sku_no, h.store_no;

INSERT INTO migrate_guard
SELECT 'goods_without_store', COUNT(*) FROM {trade}.prd_goods WHERE deleted = 0 AND (store_no IS NULL OR store_no = '');
INSERT INTO migrate_guard
SELECT 'sku_stock_divergent_by_market', COUNT(*) FROM (
    SELECT sku_no FROM {src}.prd_sku WHERE deleted = 0 GROUP BY sku_no
    HAVING MIN(stock) <> MAX(stock) OR MIN(locked_stock) <> MAX(locked_stock)) x;
INSERT INTO migrate_guard
SELECT 'store_stock_on_pool_key', COUNT(*) FROM {src}.prd_store_stock WHERE deleted = 0 AND store_no = '';

INSERT INTO {trade}.prd_store_sku
    (tenant_no, store_no, sku_no, entity_no, market, price, origin_price, sellable_qty, locked_qty)
SELECT MIN(tenant_no), store_no, sku_no, MIN(entity_no), market,
       MAX(IF(has_price = 1, price, NULL)), MAX(IF(has_price = 1, origin_price, NULL)),
       MAX(IF(has_stock = 1, sellable, NULL)), COALESCE(MAX(IF(has_stock = 1, locked, NULL)), 0)
FROM store_sku_in
GROUP BY store_no, sku_no, market;

-- ---------------------------------------------------------------- 7 门店目录（一版一行）、门店上下架、库存锁
INSERT INTO {trade}.prd_store_catalog
    (tenant_no, entity_no, store_no, catalog_no, catalog_version_no, stage, version_no, published_at,
     effective_at, frozen_content, snapshot_hash, deleted, created_at, created_by, updated_at, updated_by)
SELECT v.tenant_no, v.entity_no, v.store_no, v.catalog_no, v.catalog_version_no, 'PUBLISHED', v.revision_index, v.created_at,
       v.effective_at, v.frozen_payload, v.snapshot_hash, v.deleted, v.created_at, v.created_by, v.updated_at, v.updated_by
FROM {src}.prd_store_catalog_version v;

INSERT INTO {trade}.prd_catalog_item
    (tenant_no, entity_no, store_no, catalog_version_no, goods_no, enabled, platform_suspended,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT COALESCE(tenant_no, 'MAIN'), entity_no, store_no, '', goods_no, on_sale, COALESCE(platform_suspended, 0),
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_store_goods;

INSERT INTO {trade}.prd_stock_lock
    (tenant_no, entity_no, lock_no, store_no, source_store_no, sku_no, qty, presale, fulfillment_ref, status,
     locked_at, settled_at, source_manifest_hash, lifecycle_version, confirmation_operation_no,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT l.tenant_no, (SELECT MIN(s.entity_no) FROM {src}.prd_sku s WHERE s.sku_no = l.sku_no),
       l.lock_no,
       -- 主体级现货锁跟着余额走：余额并进了本店行，释放与扣减也要落到那一行
       IF((l.store_no IS NULL OR l.store_no = '') AND COALESCE(l.presale, 0) = 0,
          NULLIF((SELECT h.store_no FROM pool_home h WHERE h.sku_no = l.sku_no), ''), l.store_no),
       l.source_store_no, l.sku_no, l.qty, l.presale, l.fulfillment_ref, l.status,
       l.locked_at, l.settled_at, l.source_manifest_hash, COALESCE(l.lifecycle_version, 0), l.confirmation_operation_no,
       l.deleted, l.version, l.created_at, l.created_by, l.updated_at, l.updated_by
FROM {src}.prd_stock_lock l;

-- 主体类目库存开关 → 门店分类表的主体级行（门店号、目录号空串；行编号按主体 + 平台类目算）
INSERT INTO {trade}.prd_store_category
    (tenant_no, entity_no, store_no, category_no, catalog_no, platform_category_no, inv_managed,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT COALESCE(tenant_no, 'MAIN'), entity_no, '', CONCAT('INV', LEFT(SHA2(CONCAT(entity_no, '|', category_no), 256), 29)),
       '', category_no, managed, deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_entity_category_inv WHERE deleted = 0;

-- ---------------------------------------------------------------- 8 会员营销库：收藏、专题
INSERT INTO {member}.mbr_favorite
    (tenant_no, user_no, favorite_type, entity_no, goods_no, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT COALESCE(f.tenant_no, 'MAIN'), f.user_no, 'GOODS',
       (SELECT MIN(g.entity_no) FROM {src}.prd_goods g WHERE g.goods_no = f.goods_no),
       f.goods_no, 0, 0, f.created_at, f.created_by, f.updated_at, f.updated_by
FROM {src}.prd_goods_favorite f WHERE f.deleted = 0;

INSERT INTO {member}.prd_topic
    (tenant_no, topic_no, topic_type, title, subtitle, cover, sort, start_at, end_at, enabled, status,
     deleted, version, created_at, created_by, updated_at, updated_by)
SELECT COALESCE(tenant_no, 'MAIN'), topic_no, 'TOPIC', title, subtitle, cover, sort, start_at, end_at, 1, status,
       deleted, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_topic;

INSERT INTO {member}.prd_topic_goods
    (tenant_no, topic_no, goods_no, sku_no, entity_no, sort, deleted, version, created_at, created_by, updated_at, updated_by)
SELECT COALESCE(tenant_no, 'MAIN'), topic_no, goods_no, '', entity_no, sort, 0, version, created_at, created_by, updated_at, updated_by
FROM {src}.prd_topic_goods WHERE deleted = 0;

-- ---------------------------------------------------------------- 9 搬完的检查（行数对得上才提交）
INSERT INTO migrate_guard SELECT 'category_rows',
    (SELECT COUNT(*) FROM {src}.prd_category) - (SELECT COUNT(*) FROM {trade}.prd_category);
INSERT INTO migrate_guard SELECT 'goods_rows',
    (SELECT COUNT(*) FROM {src}.prd_goods) - (SELECT COUNT(*) FROM {trade}.prd_goods);
INSERT INTO migrate_guard SELECT 'sku_rows',
    (SELECT COUNT(*) FROM {src}.prd_sku) - (SELECT COUNT(*) FROM {trade}.prd_sku);
INSERT INTO migrate_guard SELECT 'spec_dim_rows',
    (SELECT COUNT(*) FROM {src}.prd_spec_dim) - (SELECT COUNT(*) FROM {trade}.prd_spec_dim);
INSERT INTO migrate_guard SELECT 'spec_value_rows',
    (SELECT COUNT(*) FROM {src}.prd_spec_value) - (SELECT COUNT(*) FROM {trade}.prd_spec_value);
INSERT INTO migrate_guard SELECT 'catalog_version_rows',
    (SELECT COUNT(*) FROM {src}.prd_store_catalog_version) - (SELECT COUNT(*) FROM {trade}.prd_store_catalog);
INSERT INTO migrate_guard SELECT 'stock_lock_rows',
    (SELECT COUNT(*) FROM {src}.prd_stock_lock) - (SELECT COUNT(*) FROM {trade}.prd_stock_lock);
-- 店级库存与库存池：数量与锁量逐条对上
INSERT INTO migrate_guard SELECT 'store_stock_mismatch', COUNT(*) FROM {src}.prd_store_stock o
WHERE o.deleted = 0 AND NOT EXISTS (
    SELECT 1 FROM {trade}.prd_store_sku n WHERE n.store_no = o.store_no AND n.sku_no = o.sku_no
      AND n.market = @home_market AND n.sellable_qty = o.stock AND n.locked_qty = o.locked_stock);
INSERT INTO migrate_guard SELECT 'store_price_mismatch', COUNT(*) FROM {src}.prd_store_price o
WHERE o.deleted = 0 AND NOT EXISTS (
    SELECT 1 FROM {trade}.prd_store_sku n WHERE n.store_no = o.store_no AND n.sku_no = o.sku_no
      AND n.market = IF(o.market = @legacy_market, @home_market, o.market) AND n.price <=> o.price);

COMMIT;
