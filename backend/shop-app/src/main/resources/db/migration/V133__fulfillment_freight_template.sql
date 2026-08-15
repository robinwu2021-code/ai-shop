-- 运费模板与超区规则（P-5.2.3）。
--
-- 单位口径：**重量一律克、金额一律分，都是整数**。
-- 用小数的代价是 0.1kg + 0.2kg 这类浮点误差在算钱的地方冒出来，而那是对不出账的。
--
-- 超区规则用 JSON 存而不是另开一张子表：一个模板的超区条目是几条到几十条、
-- 永远整体读整体写、没有任何单独按区域查的场景。开子表会换来一次 join
-- 和一份「删模板要不要级联删子行」的心智负担，而换不到任何查询能力。
-- （同一判断已经用在 prd_category.attr_template 上。）
--
-- ⚠️ **一期只存不算**：下单算价今天读的是商家侧 store_delivery_rule（V7）。
-- 平台模板接进算价是二期 —— 这一条是**已知的「存了暂时没人读」**，
-- 记在 TDD-运营端履约调度 §五 T3，不藏着。

CREATE TABLE IF NOT EXISTS ful_freight_template
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    template_no VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    first_weight_gram INT(11) NOT NULL DEFAULT 1000 COMMENT '首重（克）。下限 100 —— 首重 0 克意味着「拿起来就收首重费」，那是配置错误不是策略',
    first_fee BIGINT(20) NOT NULL DEFAULT 0 COMMENT '首重费（分）',
    add_weight_gram INT(11) NOT NULL DEFAULT 500 COMMENT '续重单位（克）。必须 > 0，否则续重费无从计算',
    add_fee BIGINT(20) NOT NULL DEFAULT 0 COMMENT '每个续重单位的费用（分）',
    free_threshold BIGINT(20) NOT NULL DEFAULT 0 COMMENT '满多少分免邮；0 = 不免邮',
    is_default TINYINT(4) NOT NULL DEFAULT 0 COMMENT '默认模板**不能归档** —— 归档之后新商家没有模板可用',
    out_of_range TEXT DEFAULT NULL COMMENT 'JSON 数组：[{region,action:REJECT|SURCHARGE,surcharge}]。同一区域只能有一条，否则命中哪条取决于顺序',
    archived_at BIGINT(20) DEFAULT NULL COMMENT '归档时间戳（G1 软删除）。硬删会把历史订单的运费依据一起抹掉 —— 之后谁也说不清那单当时为什么收了 8 元',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_freight_template_no (template_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='平台运费模板与超区规则';

-- 一条默认模板。**必须有**：archiveFreightTemplate 的「默认模板不能归档」那条闸
-- 需要库里真的存在一个 is_default=1 的行，否则那条规则永远走不到，
-- 而它恰恰是防「归档之后新商家没有模板可用」的那一道。
INSERT INTO ful_freight_template
(template_no, name, first_weight_gram, first_fee, add_weight_gram, add_fee, free_threshold, is_default, out_of_range, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('FT0001', '默认运费模板', 1000, 800, 500, 200, 9900, 1,
 '[{"region":"新疆维吾尔自治区","action":"SURCHARGE","surcharge":2000},{"region":"西藏自治区","action":"REJECT","surcharge":0}]',
 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
