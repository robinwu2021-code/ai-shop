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
    -- **叫 notify_no 而不是 log_no**：后者与 mch_staff_log.log_no 同名不同义，
    -- 守卫（schema-lineage）当场拦下。按名字 join 会把发送记录连到员工操作日志上，
    -- 两边都有值，不报错。新表改个名比登记一条「同名不同义」便宜得多。
    notify_no       VARCHAR(32)  NOT NULL COMMENT '业务主键',
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
    UNIQUE KEY uk_notify_no (notify_no),
    -- 列表页默认按时间倒序 + 按渠道筛，这两个是主查询形状
    KEY idx_notify_channel_time (channel, created_at),
    KEY idx_notify_status_time (status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '短信/邮件发送记录';

-- ⚠️ **功能点（菜单项）不在这条迁移里**。
-- 加了它，nav.ts 里就得有一个 `/system?tab=notify-log` 的叶子，
-- 而那个 tab 的页面还没做 —— 于是菜单上会出现一个点进去什么都没有的入口。
-- 守卫（nav-function-point）当场把这件事拦下来了，拦得对：
-- **功能点与页面必须同批落地**，先落一半的表现是「菜单有、页面空」，
-- 而运营看不出那是没做还是坏了。做 ops-web 页面时再补一条迁移。
