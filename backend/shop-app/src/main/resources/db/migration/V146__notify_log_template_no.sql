-- 发送记录记下**我们自己的业务模板号**。
--
-- 此前 sys_notify_log 只有 template_code —— 那是**通道方的**东西：
-- 短信是阿里云的 SMS_xxx，邮件干脆存的是主题。于是没有任何一条路能回答
-- 「TPL_SMS_OTP 这条模板最近发了多少次」。
--
-- 症状很具体：运营端模板列表的「30 天发送量」按 msg_message（站内信收件箱）
-- 统计，而外发通道根本不写那张表 —— 七条模板**永远显示 0**。
-- `TPL_SMS_OTP` 两天真发了 13 次，页面却告诉运营「这条没人用」，
-- 而那正是判断「哪条模板可以下线」的唯一依据。
--
-- 加一列而不是改 template_code 的语义：两者都要留着。
-- 排查时要的是通道方那个码（拿它去通道后台查回执），
-- 盘点时要的是我们这个号（它对应运营能改的那份模板）。
--
-- 存量行留空：它们发生在这一列存在之前，编一个值进去比留空更糟 ——
-- 留空是「不知道」，编值是「记错了」。

ALTER TABLE sys_notify_log
    ADD COLUMN template_no VARCHAR(64) DEFAULT NULL COMMENT '平台业务模板号 msg_template.template_no；自由文本发送为空';

-- 统计要按 (template_no, created_at) 扫：模板列表每行都要数一次近 30 天
CREATE INDEX idx_notify_log_template ON sys_notify_log (template_no, created_at);
