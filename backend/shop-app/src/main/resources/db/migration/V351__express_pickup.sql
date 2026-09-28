-- 快递代下单的取件单（TDD-快递100商家寄件 · ADR-028）。
--
-- 一张子单可以有多张取件单（取消或失败后重新叫），但同时只能有一张没结束的 ——
-- 这条由服务层判，不靠唯一键：结束态的旧单要留着对账。
--
-- 运单号回填到 ord_sub_order.express_no 走的是原发货链路；这里只记通道那一侧的事：
-- 任务号、快递员、计费重量、运费，以及已经记进商家欠款的累计额。

CREATE TABLE IF NOT EXISTS ord_express_pickup
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    pickup_no VARCHAR(64) NOT NULL COMMENT '我方取件单号，下单时作为 thirdOrderId 传给通道',
    sub_order_no VARCHAR(64) NOT NULL,
    order_no VARCHAR(64) NOT NULL,
    entity_no VARCHAR(64) NOT NULL COMMENT '商家。数据域锚点，运费记到它的欠款上',
    store_no VARCHAR(64) DEFAULT NULL COMMENT '寄件门店',
    provider VARCHAR(16) NOT NULL COMMENT 'KUAIDI100',
    carrier VARCHAR(16) NOT NULL COMMENT '快递公司，微信 delivery_id',
    task_id VARCHAR(64) DEFAULT NULL COMMENT '通道任务号，回调按它认单',
    provider_order_id VARCHAR(64) DEFAULT NULL,
    tracking_no VARCHAR(64) DEFAULT NULL COMMENT '运单号',
    status VARCHAR(16) NOT NULL COMMENT 'CREATED / ACCEPTED / PICKED / DONE / CANCELLED / FAILED',
    provider_status INT(11) DEFAULT NULL COMMENT '通道原始状态码，排查用',
    weight_g INT(11) NOT NULL COMMENT '商家申报重量（克）',
    charged_weight_g INT(11) DEFAULT NULL COMMENT '计费重量（克），快递员称重后回传',
    freight_minor BIGINT(20) DEFAULT NULL COMMENT '折后运费（分），平台实付',
    list_price_minor BIGINT(20) DEFAULT NULL COMMENT '标准运费（分）',
    freight_booked_minor BIGINT(20) NOT NULL DEFAULT 0 COMMENT '已记进商家欠款的累计运费（分）',
    courier_name VARCHAR(64) DEFAULT NULL,
    courier_mobile VARCHAR(32) DEFAULT NULL,
    fail_reason VARCHAR(255) DEFAULT NULL,
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_express_pickup_no (pickup_no),
    KEY idx_express_pickup_task (task_id),
    KEY idx_express_pickup_sub (sub_order_no),
    KEY idx_express_pickup_entity (entity_no, status)
) COMMENT='快递代下单取件单';
