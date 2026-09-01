-- 支付域自己的设置表（M2 · TDD-支付域-核心边界与迁移任务 §A1）
--
-- 这四个 key 从来就是资金域的知识，只是当初图省事存进了 sys_setting（平台通用设置）：
--   points.client.policy    端积分策略 —— 哪个端能发/能用积分
--   points.config           积分配置 —— 汇率、有效期、兜底比例
--   finance.tax-rule        个税代扣规则 —— 提现时扣多少
--   finance.invoice-title   平台开票信息 —— 供应商照着它开票
--
-- 搬过来之后支付域不再经 SettingPort 反向问主应用，那条依赖直接消失。
--
-- **没有数据迁移**：2026-09-01 查过线上 sys_setting 只有 3 条，
-- 这四个 key 一条都没有 —— 从没被运营改过，一直在用代码里的默认值。
-- 所以这里只建表；旧表里那四个 key 将来若出现（不该出现），以本表为准。
--
-- 不写 ENGINE / CHARSET / COLLATE：跟随库默认。
-- 写死 utf8mb4_uca1400_ai_ci 是 MariaDB 私有排序规则，MySQL 认不出来
-- （存量 110 处那笔账另算，见 TDD-支付域-实施方案与排期）。
CREATE TABLE IF NOT EXISTS pay_setting (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    setting_key   VARCHAR(64)  NOT NULL COMMENT '设置键，与搬家前的 sys_setting.setting_key 同名',
    setting_value TEXT         NOT NULL COMMENT 'JSON 文本。结构由各自的 VO 定义',
    remark        VARCHAR(255) DEFAULT NULL COMMENT '这条设置改动的影响面，写给下一个改它的人',
    tenant_no     VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by    VARCHAR(64)  DEFAULT NULL,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by    VARCHAR(64)  DEFAULT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_pay_setting_key (tenant_no, setting_key)
) COMMENT='支付域设置。归 pay —— D2 拆库时跟着支付域走';
