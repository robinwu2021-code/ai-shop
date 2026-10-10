-- 商品参数（产地 / 保质期 / 材质…）。
--
-- 规格库里 usage_type=PROP 的那批维度**一直没有落脚处**：平台给类目配好了，
-- 商家却看不到，因为建品页只有「规格」这一种容器，而规格是用来分 SKU 的。
-- 混进去的后果是「不锈钢 × 24cm × 黑色」变成一个要单独定价备货的行，
-- 而他其实只想说「这口锅是不锈钢的」。
--
-- 为什么不复用 spec_groups：它的每一项都会进笛卡尔积生成 SKU，
-- 混进不分 SKU 的东西，价格表会凭空多出几倍行 —— 两者形状相同、语义相反。
--
-- 存 JSON 而不是建表：参数是**读出来整份展示**的，没有按单个参数查询/聚合的场景
-- （筛选走的是值编号，那在 prd_spec_value 里已经有了）。
-- 与同表的 spec_groups / detail_images 同一口径。
--   [{"dimNo":"SD_ORIGIN","valueNo":"SV_LOCAL","code":"O_LOCAL","label":"本地"}]
--   量纲型（功率、净重）没有 valueNo，只有 label：[{"dimNo":"SD_POWER","label":"800W"}]
ALTER TABLE prd_goods
    ADD COLUMN IF NOT EXISTS params TEXT DEFAULT NULL
        COMMENT '商品参数 JSON。不分 SKU，只展示与筛选';
