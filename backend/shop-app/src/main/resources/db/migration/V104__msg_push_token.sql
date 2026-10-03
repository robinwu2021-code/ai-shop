-- App 推送设备绑定（TDD-通知与消息推送 §三期，ADR-018）。
-- client_id 是聚合商设备标识（uni-push 下即个推 cid）。
--
-- 唯一键是 (receiver_type, receiver_no, platform) 而不是 client_id：
-- 一个人可以同时装 Android 手机与 iPad，各推各的；而**同一台设备换人登录时
-- 必须解绑**（登出解绑 + 下面的 client_id 索引用于反查抢占）——
-- 共用设备的门店换班，前一个账号的订单不能推到下一个人手上，那是隐私事故。
CREATE TABLE IF NOT EXISTS msg_push_token
(
    id BIGINT(20) NOT NULL AUTO_INCREMENT,
    receiver_type VARCHAR(16) NOT NULL COMMENT '收件人类型 USER/STAFF',
    receiver_no VARCHAR(64) NOT NULL COMMENT '收件人编号（userNo）',
    platform VARCHAR(16) NOT NULL COMMENT 'APP_ANDROID / APP_IOS',
    client_id VARCHAR(128) NOT NULL COMMENT '聚合商设备标识（个推 cid）',
    tenant_no VARCHAR(32) NOT NULL DEFAULT 'MAIN',
    created_at DATETIME NOT NULL,
    created_by VARCHAR(64) DEFAULT NULL,
    updated_at DATETIME NOT NULL,
    updated_by VARCHAR(64) DEFAULT NULL,
    version BIGINT(20) NOT NULL DEFAULT 0,
    deleted TINYINT(4) NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_push_token_receiver (receiver_type, receiver_no, platform),
    KEY idx_push_token_client (client_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_uca1400_ai_ci COMMENT='App 推送设备绑定';
