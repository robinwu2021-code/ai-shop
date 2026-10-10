-- 自提点上报的缺件 / 破损（P-5.1.2 缺货标记回传 · B-10.3.4）。
--
-- **为什么现在才需要这张表**：`PickupService.reportShortage(pickupNo, subOrderNo, kind, skuNo, note)`
-- 今天收下了 skuNo 却**原地把它丢掉** —— 只往订单时间线追加一句
-- 「自提点上报短少：xxx」。买家看得到，而平台侧「这个点今天哪个 SKU 缺了几件」
-- 无从算起：一句自由文本没法聚合。
--
-- 于是平台分拣汇总里的 shortQty 只能恒为 0，而页面上那个红色徽标会**永远不亮**。
-- 一个永远不亮的告警等于没有告警，比没这一列更坏：看的人会以为「今天没缺件」。
--
-- **只留痕，不改状态、不退款**（与既有 reportException 同口径）：
-- 短少的责任在供货方还是承接方尚未定（矩阵 M4），自动退款等于默认平台兜底。

CREATE TABLE IF NOT EXISTS ful_shortage_report
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    sub_order_no VARCHAR(64) NOT NULL COMMENT '哪一单缺的 —— 责任认定要能追到具体买家的单',
    pickup_no VARCHAR(64) NOT NULL,
    sku_no VARCHAR(64) DEFAULT NULL COMMENT '哪个 SKU。分拣是按规格分堆的，缺件也必须到 SKU；到商品就分不出是哪个规格少了',
    kind VARCHAR(16) NOT NULL DEFAULT 'SHORTAGE' COMMENT 'SHORTAGE=短少 / DAMAGE=破损。两者的售后路径不同',
    qty INT(11) NOT NULL DEFAULT 1 COMMENT '缺件数。默认 1 —— 端上今天只报「缺了」不报「缺几件」，给 0 会让汇总恒为 0',
    note VARCHAR(255) DEFAULT NULL COMMENT '上报人写的说明，原样存',
    reporter_no VARCHAR(64) DEFAULT NULL COMMENT '上报人 userNo —— 缺件是责任判定的输入，必须追到人',
    at BIGINT(20) NOT NULL COMMENT '上报时刻',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_shortage_pickup_sku (pickup_no,sku_no),
    KEY idx_shortage_sub_order (sub_order_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='自提点缺件上报（append-only，只留痕不改状态）';
