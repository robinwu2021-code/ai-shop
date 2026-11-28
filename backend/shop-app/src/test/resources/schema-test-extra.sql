-- 测试库的补充种子。**只在测试里执行**，不进任何迁移。
--
-- 为什么需要它：S4 让下单走网关之后，通道解析取的是
-- 「运营开着的 ∩ 网关有实现的」。而测试库里：
--   · WECHAT / ALIPAY 是 enabled=1，但它们的网关要凭证，没装配；
--   · TEST 的网关总是装配，而它 enabled=0（默认关是刻意的，
--     不能让一条假通道出现在生产的真实渠道列表里）。
-- 两边一交，候选集是空的 —— 30 个走支付链路的场景测试全红。
--
-- 所以测试库把 TEST 开起来。**这不是把生产的默认值改掉**，
-- 而是给测试环境配一个「有实现的通道」——
-- 正如生产环境将来会配真通道的凭证。
UPDATE sys_pay_channel SET enabled = 1 WHERE pay_channel = 'TEST';

-- ── V295 的回填结果（通道 × 市场）──────────────────────────────────
--
-- **为什么要在这里手写一遍**：schema-test.sql 的生成器<b>刻意丢掉
-- INSERT … SELECT</b>（它的注释写着「那是数据回填，不是种子」）。
-- 于是 V295 的回填在 H2 里根本不执行，sys_pay_channel_market 是空的。
--
-- 而空表恰好命中「无行 = 不限市场」这条语义 —— 所有断言照样绿，
-- <b>而绿的原因是「谁都不受限」，不是「回填对了」</b>。
-- 这种假绿最难发现：判据本身没测到要测的那件事。
--
-- 下面这两行是**生产库当场查出来的回填结果**（2026-09-02）：
--   WECHAT ["CN"] · ALIPAY ["CN"] · TEST NULL（不限市场，V288 刻意留空）
-- 回填逻辑一改，这里对不上就该有用例变红。
INSERT IGNORE INTO sys_pay_channel_market
    (pay_channel, market, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
    ('WECHAT', 'CN', 'MAIN', '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0),
    ('ALIPAY', 'CN', 'MAIN', '2026-09-02 00:00:00', 'SYSTEM', '2026-09-02 00:00:00', 'SYSTEM', 0, 0);

-- 平台标准品种子：schema-test.sql 写在旧表 prd_spu_std，商品代码读门店交易库的 prd_standard_goods
-- （TDD-商品领域迁到门店交易库 批次 1，列完全一致）。旧表随 schema-test.sql 退场时，种子直接改写进新表、删掉这段。
INSERT INTO prd_standard_goods
    (std_no, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords,
     status, ref_count, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT std_no, category_no, title, title_i18n, subtitle, cover, images, spec_groups, keywords,
       status, ref_count, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted
FROM prd_spu_std s
WHERE NOT EXISTS (SELECT 1 FROM prd_standard_goods g WHERE g.std_no = s.std_no);

-- 类目规格种子：schema-test.sql 写在旧表 prd_category_spec / prd_category_spec_value，
-- 商品代码读门店交易库的 prd_spec_usage（TDD-商品领域迁到门店交易库 批次 2-d）：
-- 类目绑定 = scope_type CATEGORY 的维度级行（value_no 空串），取值子集 = 同范围的取值级行。
-- 旧表随 schema-test.sql 退场时，种子直接改写进新表、删掉这段。
INSERT INTO prd_spec_usage
    (tenant_no, scope_type, scope_ref, dim_no, usage_type, is_primary, required, sort, status,
     value_no, created_at, created_by, updated_at, updated_by, version, deleted)
SELECT tenant_no, 'CATEGORY', category_no, dim_no, usage_type, is_primary, required, sort, status,
       '', created_at, created_by, updated_at, updated_by, version, deleted
FROM prd_category_spec c
WHERE NOT EXISTS (SELECT 1 FROM prd_spec_usage u WHERE u.scope_type = 'CATEGORY'
                  AND u.scope_ref = c.category_no AND u.dim_no = c.dim_no AND u.value_no = '');
INSERT INTO prd_spec_usage
    (tenant_no, scope_type, scope_ref, dim_no, sort, status, value_no, label_override,
     created_at, created_by, updated_at, updated_by, version, deleted)
SELECT tenant_no, 'CATEGORY', category_no, dim_no, sort, 'ACTIVE', value_no, label_override,
       created_at, created_by, updated_at, updated_by, version, deleted
FROM prd_category_spec_value v
WHERE NOT EXISTS (SELECT 1 FROM prd_spec_usage u WHERE u.scope_type = 'CATEGORY'
                  AND u.scope_ref = v.category_no AND u.dim_no = v.dim_no AND u.value_no = v.value_no);

-- 品类推荐种子（批次 2-e，原平台规格模板的品类兜底）：直接按门店交易库的形状写，不再从旧表 prd_spec_template 复制。
-- 每条推荐指向规格库已有的维度，叫法不同的记在 label_override（生鲜的「约1斤」就是规格库的 500g）。
-- 旧表里普通品类那条叫「规格」，规格库禁止这类万能词作维度名，这里就是件数维度 SD_COUNT（规格库里叫「数量」）。
-- 旧表的 16 条类目级模板不再搬：类目选定后只用规格库的类目绑定，那 16 条早已读不到。
INSERT IGNORE INTO prd_spec_usage (scope_type, scope_ref, dim_no, value_no, label_override, sort, status)
VALUES
    ('CATEGORY_TYPE', 'FRESH', 'SD_WEIGHT', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_WEIGHT', 'SV_WEIGHT_W500G', '约1斤', 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_WEIGHT', 'SV_WEIGHT_W1KG', '约2斤', 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_WEIGHT', 'SV_WEIGHT_W2500G', '约5斤', 30, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_WEIGHT', 'SV_WEIGHT_W5KG', '约10斤', 40, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_PACK', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_PACK', 'SV_PACK_PBULK', NULL, 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_PACK', 'SV_PACK_PBAG', NULL, 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'ACTIVE'),
    ('CATEGORY_TYPE', 'FRESH', 'SD_PACK', 'SV_PACK_PGIFT', '礼盒', 40, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_COUNT', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_COUNT', 'SV_COUNT_C1', NULL, 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_COUNT', 'SV_COUNT_C2', NULL, 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_COUNT', 'SV_COUNT_C5', NULL, 30, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_COUNT', 'SV_COUNT_CCASE', NULL, 40, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_PACK', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_PACK', 'SV_PACK_PBAG', NULL, 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_PACK', 'SV_PACK_PBOTTLE', NULL, 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_PACK', 'SV_PACK_PBOX', NULL, 30, 'ACTIVE'),
    ('CATEGORY_TYPE', 'NORMAL', 'SD_PACK', 'SV_PACK_PCAN', NULL, 40, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_DURATION', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_DURATION', 'SV_DURATION_D30', NULL, 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_DURATION', 'SV_DURATION_D60', NULL, 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_DURATION', 'SV_DURATION_D90', NULL, 30, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_DURATION', 'SV_DURATION_D120', NULL, 40, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_HEADCOUNT', '', NULL, 0, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H1', NULL, 10, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H2', NULL, 20, 'ACTIVE'),
    ('CATEGORY_TYPE', 'SERVICE', 'SD_HEADCOUNT', 'SV_HEADCOUNT_H3', NULL, 30, 'ACTIVE');
