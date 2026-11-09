-- 商户 App 问题回报与版本检查（TDD-商户App问题回报与版本 §2「库表与迁移」）。
--
-- 一、问题回报落进已有的客服工单 notify_ticket，不另起表：回报就是一张工单，
--    客服在运营端原有的工单页按同一个 TK 号处理。存量行 source 落默认值 C_USER，C 端行为不变；
--    C 端「我的工单」与商户「我的回报」都加 source 条件（两套账号号段只是恰好不撞，不能靠它隔离）。
-- 二、版本策略一张小表 sys_app_version：一个 App、一个平台一行。不预置数据 ——
--    「没配置 → App 不提示」就是首发状态。
--
-- 166 应用库账号没有建表 / 改表权限：这条迁移要由 root 先执行（见项目记忆「166 DDL 迁移需手工」）。

ALTER TABLE notify_ticket
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'C_USER' COMMENT 'C_USER / B_MERCHANT；C 端与商户互不可见靠它',
    ADD COLUMN category VARCHAR(16) DEFAULT NULL COMMENT '商户回报分类 BUG / PAYMENT / IDEA / OTHER',
    ADD COLUMN store_no VARCHAR(64) DEFAULT NULL COMMENT '提交时所在门店（已核对属于该账号；核对不过不存）',
    ADD COLUMN store_dropped TINYINT(1) NOT NULL DEFAULT 0 COMMENT '带了门店号但不属于该账号而被丢弃',
    ADD COLUMN client_version VARCHAR(32) DEFAULT NULL COMMENT 'App 版本（超长截断）',
    ADD COLUMN client_platform VARCHAR(16) DEFAULT NULL COMMENT 'ANDROID / IOS / OTHER',
    ADD COLUMN error_code VARCHAR(64) DEFAULT NULL COMMENT '从哪个错误进来（超长截断）',
    ADD COLUMN page VARCHAR(128) DEFAULT NULL COMMENT '从哪一页进来（超长截断）',
    ADD COLUMN diagnostics JSON DEFAULT NULL COMMENT '清洗后的诊断数组（≤300 条，只留 at/method/path/outcome）；90 天后清空';

CREATE INDEX idx_ticket_source_user ON notify_ticket (source, user_no);

CREATE TABLE IF NOT EXISTS sys_app_version
(
    id                    BIGINT(20)   NOT NULL AUTO_INCREMENT,
    app_code              VARCHAR(32)  NOT NULL COMMENT 'MERCHANT（以后 POS 安卓 App 加一行数据，不加表）',
    platform              VARCHAR(16)  NOT NULL COMMENT 'ANDROID / IOS',
    latest_version        VARCHAR(32)  DEFAULT NULL COMMENT '最新版；空 = 不提示更新',
    min_supported_version VARCHAR(32)  DEFAULT NULL COMMENT '最低支持版；低于它的包写请求回 10426',
    notes                 JSON         DEFAULT NULL COMMENT '{"zh-Hant":[…],"zh-Hans":[…],"en":[…],"ar":[…]}，zh-Hant 必填',
    store_url             VARCHAR(255) DEFAULT NULL COMMENT '只收 https://apps.apple.com/… 或 https://play.google.com/…',
    tenant_no             VARCHAR(32)  NOT NULL DEFAULT 'MAIN',
    created_at            DATETIME     NOT NULL,
    created_by            VARCHAR(64)  DEFAULT NULL,
    updated_at            DATETIME     NOT NULL,
    updated_by            VARCHAR(64)  DEFAULT NULL,
    version               BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '运营端改动的 revision（乐观锁）',
    deleted               TINYINT(4)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_version (tenant_no, app_code, platform)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='App 版本策略（最新版 / 最低支持版 / 更新说明 / 商店地址）';
