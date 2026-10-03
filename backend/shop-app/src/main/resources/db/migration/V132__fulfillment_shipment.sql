-- 快递运单记录与轨迹（P-5.2.1 快递面单/轨迹）。
--
-- **为什么不能只靠 ord_sub_order.express_no**：那是一个字符串列，
-- 换一次单号旧值就没了。而「换单号」恰恰是运营在这一页唯一能做的动作，
-- 也是事后对不上时的唯一线索 —— 覆盖掉等于把线索删了。
--
-- ⚠️ **一期不对接快递鸟/菜鸟**（ADR-005 §5：一期只做快递 + 商家自送）。
-- 这两张表做的是「存住 + 展示」：运单号回填、轨迹留痕。
-- 真接回传要密钥托管、回调鉴权、重试与对账，是一个完整子系统，不是补两张表。

CREATE TABLE IF NOT EXISTS ful_shipment
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    shipment_no VARCHAR(64) NOT NULL COMMENT '平台侧主键，**不是快递单号** —— 换单号时运单记录不变，靠的就是这个键',
    sub_order_no VARCHAR(64) NOT NULL COMMENT '关联的子订单（一子单一运单）',
    carrier VARCHAR(16) NOT NULL COMMENT 'SF / JD / YTO。建单时取当时优先级最高的启用运力并快照',
    waybill_no VARCHAR(64) DEFAULT NULL COMMENT '承运商的快递单号',
    status VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED/PICKED_UP/IN_TRANSIT/DELIVERED/EXCEPTION。**EXCEPTION 不是终态**：疑难件可能之后又派送成功',
    receiver VARCHAR(64) DEFAULT NULL COMMENT '收件人姓名快照。不现查 usr_address —— 那张表可改可删，改了之后运营看到的收件人跟货不是一个人',
    region VARCHAR(64) DEFAULT NULL COMMENT '收件地区（省 市）。超区判断看的就是它',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_shipment_no (shipment_no),
    UNIQUE KEY uk_shipment_sub_order (sub_order_no),
    -- **(carrier, waybill_no) 刻意不建唯一键**，尽管「同承运商下运单号不能重复」
    -- 是 Service 里硬判的一条规则。
    --
    -- 理由：那条规则约束的是**运营手工换单号**，而运单记录还有另一个来源 ——
    -- 从商家回填的 express_no 补齐。同一个订单的两个子单**可能共用一张面单**
    -- （一个箱子装两家的货是拆单履约的常态）。库上判唯一的话，这种完全正常的情况
    -- 会让补齐语句抛异常，而补齐发生在列表接口里 —— 症状是**整页 500**，
    -- 且报错指向一个跟运单号毫无关系的地方。
    --
    -- 换句话说：唯一性是「运营不该制造重号」，不是「库里不可能有重号」。
    -- 把前者写成后者，代价由一条正常业务路径来付。
    KEY idx_shipment_carrier_waybill (carrier,waybill_no),
    KEY idx_shipment_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='快递运单记录（平台侧）';

-- 轨迹是**承运商的事实**，平台不编。
-- 页面上没有「手工加一条轨迹」：平台自己写的轨迹一旦与承运商记录不一致，
-- 纠纷时反而站不住。这张表今天只有一个写入方 —— 换单号那条留痕。
CREATE TABLE IF NOT EXISTS ful_shipment_trace
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    shipment_no VARCHAR(64) NOT NULL,
    at BIGINT(20) NOT NULL COMMENT '轨迹时刻',
    text VARCHAR(255) NOT NULL COMMENT '节点描述，原样来自承运商（平台写的那条会注明是平台写的）',
    location VARCHAR(64) DEFAULT NULL COMMENT '所在城市/网点',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id),
    KEY idx_trace_shipment_at (shipment_no,at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='快递轨迹节点（append-only）';
