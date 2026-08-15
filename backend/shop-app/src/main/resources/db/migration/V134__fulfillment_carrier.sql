-- 第三方运力接入配置（P-5.2.4）。
--
-- ⚠️ **标的是二期**：这一批只做**配置存储 + 启停**，不接任何真实物流 API
-- （ADR-005 §5：即时配送全外接，一期只做快递 + 商家自送）。
--
-- **这张表里没有密钥列，只有一个「配没配」的布尔。**
-- 密钥该进配置中心/KMS，不该躺在业务表里；更不该出现在前端契约里，哪怕是脱敏的。
-- 有了列就迟早有人把真密钥填进去，然后它会跟着一次 SELECT * 出现在日志里。
--
-- 这一页配错的后果不是「显示不对」，而是**订单发不出去**，所以三条闸在 Service 里硬判：
--   · 没配密钥不能启用 —— 启用后下单当场失败，比不启用更糟（不启用时运营知道它不可用）
--   · 还有在途单的不能停用 —— 停了之后那些单的轨迹拉不回来
--   · 不能把最后一家启用的也停掉 —— 全停之后快递单无处可下

CREATE TABLE IF NOT EXISTS ful_carrier
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    carrier VARCHAR(16) NOT NULL COMMENT 'SF / JD / YTO。一期只接这三家，改枚举比改结构便宜',
    name VARCHAR(64) NOT NULL COMMENT '展示名',
    enabled TINYINT(4) NOT NULL DEFAULT 0,
    priority INT(11) NOT NULL DEFAULT 1 COMMENT '数字越小越优先。**不允许重复** —— 同优先级时选哪家取决于查询顺序，那是隐性行为',
    account_masked VARCHAR(64) DEFAULT NULL COMMENT '接入账号，展示一律脱敏',
    api_key_configured TINYINT(4) NOT NULL DEFAULT 0 COMMENT '密钥是否已配（密钥本身不在这里，见文件抬头）',
    pickup_cutoff VARCHAR(8) NOT NULL DEFAULT '17:00' COMMENT '每日截单时间 HH:mm，过点的单顺延到次日',
    sla_hours INT(11) NOT NULL DEFAULT 48 COMMENT '承诺时效（小时），必须为正',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_carrier (carrier),
    UNIQUE KEY uk_carrier_priority (priority)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='第三方运力接入配置（一期只存不接）';

-- 三家的初始档案。**必须有种子行**：saveCarrier / setCarrierEnabled 都是「改一家已有的」，
-- 没有行的话这两条端点从第一天起就是 404，而页面上是一张空表 —— 看不出是「没配」还是「坏了」。
--
-- 三家分别对应三种状态，这不是凑数：主力（有在途单时停不掉）、备用、以及**没配密钥的那家**
-- —— 最后这家是「没配密钥不能启用」那条闸唯一能被真的走到的路径。
INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('SF', '顺丰速运', 1, 1, 'SF-****-8821', 1, '17:00', 48, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('JD', '京东物流', 1, 2, 'JD-****-3390', 1, '16:30', 72, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);

INSERT INTO ful_carrier
(carrier, name, enabled, priority, account_masked, api_key_configured, pickup_cutoff, sla_hours, tenant_no, created_at, created_by, updated_at, updated_by, version, deleted)
VALUES
('YTO', '圆通速递', 0, 3, NULL, 0, '18:00', 96, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0);
