-- 订阅消息发送额度（TDD-通知与消息推送 §4.4）。
-- 微信一次性订阅 = 用户点一次「允许」攒一次发送机会，发一条耗一条。
-- accepted 只记最近一次选择（控制要不要再弹授权框），能不能发看 quota ——
-- 只看 accepted 的话，一次授权会被反复用来发无限条，微信侧会以 43101 拒绝，
-- 而我们这边的发送记录还都是「已发送」。
ALTER TABLE msg_subscribe
    ADD COLUMN quota INT NOT NULL DEFAULT 0 COMMENT '未消耗的发送额度（一次授权=一次发送）' AFTER accepted;
