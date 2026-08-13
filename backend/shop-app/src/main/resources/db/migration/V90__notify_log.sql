-- 短信/邮件发送记录。
--
-- 今天发出去之后**什么都不留**：发没发、发给谁、通道怎么答的，只能翻服务器日志，
-- 而日志会轮转、会被采集走。出问题时最常见的问句是「他到底收没收到」，
-- 没有这张表就答不了 —— 只能让用户再试一次，而如果真的是通道的问题，再试还是收不到。
--
-- **target 存掩码**（138****8888 / r***n@neargo.ai）：这张表运营都看得到，
-- 而收件人是用户的手机号与邮箱。要查具体一条，靠 provider_msg_id 去通道后台查 ——
-- 通道那边本来就有明文，我方不必再留一份。
--
-- 号段取 V90 而不是接着 V87：V78-V87 是并行会话未提交的迁移，
-- 撞号会让两边的本地库各迁一半。

CREATE TABLE IF NOT EXISTS sys_notify_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    log_no          VARCHAR(32)  NOT NULL COMMENT '业务主键',
    channel         VARCHAR(16)  NOT NULL COMMENT 'SMS / MAIL',
    biz_type        VARCHAR(32)  NOT NULL COMMENT 'OTP / OPS_INIT_PASSWORD / OPS_RESET_PASSWORD / TEST',
    target          VARCHAR(64)  NOT NULL COMMENT '收件人，**掩码**。明文不落库',
    template_code   VARCHAR(64)           COMMENT '短信为阿里云模板号；邮件为主题',
    status          VARCHAR(16)  NOT NULL COMMENT 'SENT / FAILED',
    error           VARCHAR(512)          COMMENT '通道返回的错误码与消息，失败时才有',
    provider_msg_id VARCHAR(64)           COMMENT '阿里云 BizId / 邮件 Message-ID —— 找通道对账靠它',
    operator_no     VARCHAR(32)           COMMENT '谁触发的。自动发出的（OTP）为空',
    client_ip       VARCHAR(64)           COMMENT '触发来源，排查滥用用',
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notify_log_no (log_no),
    -- 列表页默认按时间倒序 + 按渠道筛，这两个是主查询形状
    KEY idx_notify_channel_time (channel, created_at),
    KEY idx_notify_status_time (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '短信/邮件发送记录';

-- 运营端功能点：发送记录列表 + 测试发送。
-- **复用 message:template:read / update**，不新增权限码 ——
-- 维护消息模板的与看发送记录的是同一批人，多一个码只增加配置负担。
INSERT INTO sys_function_point (point_code, function_code, name, group_name, href, ui_perm_code, perm_code, backend_status, ui_ready, matrix_code, point_type, sort, created_at, updated_at)
SELECT 'OPS_SYSTEM__TAB_NOTIFY_LOG', 'OPS_SYSTEM', '发送记录', '消息', '/system?tab=notify-log', 'message:template:read', 'message:template:read', 'IMPLEMENTED', 1, 'P-14.3', 'MENU', 40, NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_function_point x WHERE x.point_code='OPS_SYSTEM__TAB_NOTIFY_LOG');

INSERT INTO sys_role_point (role_code, point_code, end_code, created_at, updated_at)
SELECT 'SUPER_ADMIN', 'OPS_SYSTEM__TAB_NOTIFY_LOG', 'OPS', NOW(), NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_role_point x WHERE x.role_code='SUPER_ADMIN' AND x.point_code='OPS_SYSTEM__TAB_NOTIFY_LOG');
