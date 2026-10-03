-- 发送记录加「供应商」维度（设计：触达推送中台-模块抽象 · N3）。
--
-- 此前个推/FCM/APNs 三家都写 channel=PUSH，挤在一格里 —— 运营端体检与记录都拆不出
-- 「今天 FCM 发了多少、APNs 失败几条」。加一列 provider，PushRouter 发送时按路由到的
-- 供应商回填；单供应商通道（SMS→ALI 等）由 NotifyLogWriter 自动推出。
--
-- 可空：旧行 provider 为 NULL（历史记录不追溯归因）；新行由写入侧填。
ALTER TABLE sys_notify_log
    ADD COLUMN provider VARCHAR(16) DEFAULT NULL COMMENT '供应商 ALI/SMTP/WECHAT/GETUI/FCM/APNS；旧行为空' AFTER channel;

-- 体检按 (channel, provider, status, created_at) 数近段计数，补一个覆盖索引
CREATE INDEX idx_notify_log_provider ON sys_notify_log (channel, provider, status, created_at);
