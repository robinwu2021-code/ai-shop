-- 订单服务 2.0 · W1 地基：发货单层 ord_shipment + 订单行发货归属
--
-- 纯加表 + 加列，向后兼容：现有代码不读新表/新列，Flyway 应用后现有行为一字不变。
-- 新写路径（ai.neargo.shop.trade.order）成熟接线前，这些结构空置待用。
--
-- 发货单（交易域，一次交付）：一个订单（ord_sub_order）可多次发货/多包裹；
-- 每张快递发货单关联一条物流运单（lgs_waybill.biz_ref = shipment_no）。
-- 不写 ENGINE/CHARSET：用库默认，避开 MySQL 9.7 的 uca1400 collation（见 prod-mysql97）。

CREATE TABLE IF NOT EXISTS ord_shipment (
    id            BIGINT(20)  NOT NULL AUTO_INCREMENT,
    shipment_no   VARCHAR(40) NOT NULL COMMENT '发货单号（SH…）',
    sub_order_no  VARCHAR(40) NOT NULL COMMENT '所属订单（ord_sub_order.sub_order_no）',
    ship_store_no VARCHAR(40) DEFAULT NULL COMMENT '发货门店',
    fulfillment   VARCHAR(20) NOT NULL COMMENT '本次交付方式 EXPRESS/MERCHANT_DELIVERY/STORE_PICKUP/NEIGHBOR_PICKUP/STORE_VERIFY/APPOINTMENT',
    carrier       VARCHAR(40) DEFAULT NULL COMMENT '承运商（快递）',
    waybill_no    VARCHAR(64) DEFAULT NULL COMMENT '运单号（快递）',
    status        VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED 待揽收 / SHIPPED 在途 / DELIVERED 已签收 / RECEIVED 买家确认 / VOIDED 改号作废',
    voided_at     BIGINT(20)  DEFAULT NULL COMMENT '作废时刻',
    void_reason   VARCHAR(64) DEFAULT NULL COMMENT '作废原因',
    superseded_by VARCHAR(40) DEFAULT NULL COMMENT '被哪张新发货单取代（改号）',
    tenant_no     VARCHAR(32) DEFAULT NULL,
    created_at    DATETIME    DEFAULT NULL,
    created_by    VARCHAR(64) DEFAULT NULL,
    updated_at    DATETIME    DEFAULT NULL,
    updated_by    VARCHAR(64) DEFAULT NULL,
    version       BIGINT(20)  NOT NULL DEFAULT 0,
    deleted       TINYINT(4)  NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ord_shipment_no (shipment_no, tenant_no),
    KEY idx_ord_shipment_sub (sub_order_no),
    KEY idx_ord_shipment_waybill (carrier, waybill_no)
) COMMENT='发货单（交易域）：一个订单可多次发货/多包裹，每张关联一条物流运单';

ALTER TABLE ord_item
    ADD COLUMN supply_store_no VARCHAR(40) DEFAULT NULL COMMENT '供货门店（订单服务 2.0）',
    ADD COLUMN shipment_no     VARCHAR(40) DEFAULT NULL COMMENT '归属发货单，发货后写（订单服务 2.0）',
    ADD COLUMN shipped_qty     INT(11)     NOT NULL DEFAULT 0 COMMENT '已发数量（订单服务 2.0）';
