-- =====================================================================
-- V387 物流独立为模块（TDD-物流模块 批 1 / ADR-032）
--
-- 1. 三张物流表改名为 lgs_ 前缀。ful_ 留给不搬的自提 / 分拣表 ——
--    前缀不分开，阶段 2「主库迁移里不再出现 lgs_* 建表」这条闸门写不出来。
--    RENAME 在 MySQL 是原子的、不拷数据。
-- 2. 运单加列：渠道订阅、微信换 token、到柜、承运商纠正、登记快照（TDD §2.2）。
--    **这一版旧代码照旧读写**（实体只改表名），新列由批 2 起的新代码使用。
-- 3. 新表 lgs_carrier_code：一家承运商在各渠道里叫什么。按渠道加列的话每接一家渠道改一次表。
--
-- 刻意没做的：
-- - 轨迹节点不加唯一键：存量里可能有重复节点（去重至今靠代码），直接加 UK 会让迁移在生产失败。
-- - 运单唯一键仍只在 biz_ref 上（原 uk_shipment_sub_order）：今天只有子单一种业务；
--   接退货运单（biz_type=RETURN）那天再换成 (biz_type, biz_ref)。
-- =====================================================================

ALTER TABLE ful_shipment RENAME TO lgs_waybill;
ALTER TABLE ful_shipment_trace RENAME TO lgs_waybill_node;
ALTER TABLE ful_carrier RENAME TO lgs_carrier;

ALTER TABLE lgs_waybill RENAME COLUMN sub_order_no TO biz_ref;

ALTER TABLE lgs_waybill
    ADD COLUMN biz_type VARCHAR(16) NOT NULL DEFAULT 'SUB_ORDER' COMMENT '业务类型：SUB_ORDER（今天唯一值）/ 将来 RETURN（售后寄回）',
    ADD COLUMN entity_no VARCHAR(32) DEFAULT NULL COMMENT '登记时快照：商家主体号（同 ord_sub_order.entity_no），运营筛选用，不回查商家表',
    ADD COLUMN store_no VARCHAR(32) DEFAULT NULL COMMENT '登记时快照：门店路由与运营筛选用',
    ADD COLUMN profile VARCHAR(8) NOT NULL DEFAULT 'SELF' COMMENT 'WX = 有微信交易单号与付款人 openid，可用微信物流全套；SELF = 其余（线下付款、APP 单）。登记时定死',
    ADD COLUMN receiver_phone_enc VARCHAR(128) DEFAULT NULL COMMENT '收件人手机号 AES-GCM 密文。订阅（顺丰 / 中通必填）与换 token（申通 / 中通必填）要用；进入终态即清空',
    ADD COLUMN receiver_phone_last4 CHAR(4) DEFAULT NULL COMMENT '收件人手机号后四位，展示与排查用',
    ADD COLUMN wx_trans_id VARCHAR(64) DEFAULT NULL COMMENT '微信支付交易单号快照（profile=WX）',
    ADD COLUMN wx_openid VARCHAR(64) DEFAULT NULL COMMENT '付款人 openid 快照（profile=WX）',
    ADD COLUMN wx_out_trade_no VARCHAR(64) DEFAULT NULL COMMENT '商户支付单号快照（profile=WX）',
    ADD COLUMN goods_brief VARCHAR(1000) DEFAULT NULL COMMENT '商品名与图（≤3 件，JSON）。微信 trace_waybill 必填',
    ADD COLUMN picked_up_at BIGINT DEFAULT NULL COMMENT '首次进入 PICKED_UP 的时刻（毫秒），只写一次',
    ADD COLUMN at_locker TINYINT NOT NULL DEFAULT 0 COMMENT '渠道子状态「投柜或驿站」（快递100 501）：物流页显示已到驿站 / 快递柜',
    ADD COLUMN carrier_corrected_from VARCHAR(16) DEFAULT NULL COMMENT '渠道纠正过承运商时的原值（快递100 autoCheck=1）。纠正要留痕，不静默覆盖',
    ADD COLUMN last_event_at BIGINT DEFAULT NULL COMMENT '最近一次推送或查询有新进展的时刻（毫秒）。补偿作业判「沉默」用',
    ADD COLUMN sub_state VARCHAR(12) NOT NULL DEFAULT 'PENDING' COMMENT '渠道订阅：PENDING / DONE / FATAL / ENDED（渠道停止跟踪）/ NA（不需要订阅：登记前已签收的存量）',
    ADD COLUMN sub_channel VARCHAR(16) DEFAULT NULL COMMENT '实际受理订阅的渠道（yto / kuaidi100 / …）。推送只认这个渠道来的',
    ADD COLUMN sub_ref VARCHAR(64) DEFAULT NULL COMMENT '渠道返回的订阅号（有的渠道给）',
    ADD COLUMN sub_attempts INT NOT NULL DEFAULT 0 COMMENT '订阅累计尝试次数',
    ADD COLUMN sub_error VARCHAR(255) DEFAULT NULL COMMENT '链上最后一次失败：渠道 + 码 + 原文',
    ADD COLUMN kd100_sub_month CHAR(6) DEFAULT NULL COMMENT '快递100 订阅计数所属自然月 yyyyMM',
    ADD COLUMN kd100_sub_count INT NOT NULL DEFAULT 0 COMMENT '快递100 本月已订阅次数（官方上限每单号每月 4 次）',
    ADD COLUMN wx_uploaded_at BIGINT DEFAULT NULL COMMENT '微信发货信息上传成功的时刻（毫秒）。换 token 的前置条件',
    ADD COLUMN bind_state VARCHAR(12) NOT NULL DEFAULT 'NA' COMMENT '微信换 token：NA（SELF 单）/ WAITING / DONE / FATAL',
    ADD COLUMN bind_error VARCHAR(255) DEFAULT NULL COMMENT '换 token 最后一次失败的码与原文',
    ADD COLUMN wx_status_checked_at BIGINT DEFAULT NULL COMMENT '最近一次调微信 query_trace 的时刻（毫秒）。物流页 10 分钟读缓存的闸';

CREATE INDEX idx_waybill_sub_state ON lgs_waybill (sub_state);

ALTER TABLE lgs_waybill_node
    ADD COLUMN channel VARCHAR(16) DEFAULT NULL COMMENT '节点来自哪个渠道（kuaidi100 / yto / …）。存量为空',
    ADD COLUMN mode VARCHAR(8) DEFAULT NULL COMMENT 'PUSH / QUERY。存量为空（都是轮询来的）';

CREATE TABLE IF NOT EXISTS lgs_carrier_code
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    carrier VARCHAR(16) NOT NULL COMMENT '我方承运商码（SF / STO / YTO …，与微信 delivery_id 同一套）',
    channel VARCHAR(16) NOT NULL COMMENT '渠道：kuaidi100 / wx / yto / …',
    code VARCHAR(32) NOT NULL COMMENT '这家承运商在该渠道里的叫法',
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carrier_channel (carrier, channel)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='承运商在各物流渠道的编码。渠道的 covers(carrier) = 这里有没有它这一行；加一家渠道 = 插几行，不改表';

-- 微信：我方码就是微信 delivery_id（ExpressCompanies 2026-09-20 用 get_delivery_list 人工核验后固化）
INSERT INTO lgs_carrier_code (carrier, channel, code, created_at, updated_at, updated_by) VALUES
('SF', 'wx', 'SF', NOW(), NOW(), 'V387'),
('ZTO', 'wx', 'ZTO', NOW(), NOW(), 'V387'),
('YTO', 'wx', 'YTO', NOW(), NOW(), 'V387'),
('YD', 'wx', 'YD', NOW(), NOW(), 'V387'),
('STO', 'wx', 'STO', NOW(), NOW(), 'V387'),
('JTSD', 'wx', 'JTSD', NOW(), NOW(), 'V387'),
('JD', 'wx', 'JD', NOW(), NOW(), 'V387'),
('YZPY', 'wx', 'YZPY', NOW(), NOW(), 'V387'),
('EMS', 'wx', 'EMS', NOW(), NOW(), 'V387'),
('DBL', 'wx', 'DBL', NOW(), NOW(), 'V387'),
('HTKY', 'wx', 'HTKY', NOW(), NOW(), 'V387'),
('FWX', 'wx', 'FWX', NOW(), NOW(), 'V387'),
('UC', 'wx', 'UC', NOW(), NOW(), 'V387'),
('ZJS', 'wx', 'ZJS', NOW(), NOW(), 'V387');

-- 快递100：照 Kuaidi100TraceProvider.CODES（查询在用的那一份）
INSERT INTO lgs_carrier_code (carrier, channel, code, created_at, updated_at, updated_by) VALUES
('SF', 'kuaidi100', 'shunfeng', NOW(), NOW(), 'V387'),
('ZTO', 'kuaidi100', 'zhongtong', NOW(), NOW(), 'V387'),
('YTO', 'kuaidi100', 'yuantong', NOW(), NOW(), 'V387'),
('YD', 'kuaidi100', 'yunda', NOW(), NOW(), 'V387'),
('STO', 'kuaidi100', 'shentong', NOW(), NOW(), 'V387'),
('JTSD', 'kuaidi100', 'jtexpress', NOW(), NOW(), 'V387'),
('JD', 'kuaidi100', 'jd', NOW(), NOW(), 'V387'),
('YZPY', 'kuaidi100', 'youzhengguonei', NOW(), NOW(), 'V387'),
('EMS', 'kuaidi100', 'ems', NOW(), NOW(), 'V387'),
('DBL', 'kuaidi100', 'debangkuaidi', NOW(), NOW(), 'V387'),
('HTKY', 'kuaidi100', 'huitongkuaidi', NOW(), NOW(), 'V387'),
('FWX', 'kuaidi100', 'fengwang', NOW(), NOW(), 'V387'),
('UC', 'kuaidi100', 'youshuwuliu', NOW(), NOW(), 'V387'),
('ZJS', 'kuaidi100', 'zhaijisong', NOW(), NOW(), 'V387');

-- 圆通直连：只覆盖圆通单
INSERT INTO lgs_carrier_code (carrier, channel, code, created_at, updated_at, updated_by) VALUES
('YTO', 'yto', 'YTO', NOW(), NOW(), 'V387');

-- ---- 存量回填（只在 MySQL 上跑；H2 测试库是空的，生成器会跳过带 JOIN 的 UPDATE）----

-- 已签收的存量不需要订阅
UPDATE lgs_waybill SET sub_state = 'NA' WHERE status = 'DELIVERED';

-- 快照：门店 / 商家；微信支付单（有微信交易单号）标 WX
UPDATE lgs_waybill w
    JOIN ord_sub_order s ON s.sub_order_no = w.biz_ref
SET w.store_no = s.store_no, w.entity_no = s.entity_no;

UPDATE lgs_waybill w
    JOIN ord_sub_order s ON s.sub_order_no = w.biz_ref
    JOIN ord_order o ON o.order_no = s.order_no
SET w.profile = 'WX',
    w.wx_trans_id = o.pay_trade_no,
    w.bind_state = CASE WHEN w.display_token IS NOT NULL AND w.display_token <> '' THEN 'DONE' ELSE 'WAITING' END
WHERE o.pay_trade_no IS NOT NULL AND o.pay_trade_no <> '';
