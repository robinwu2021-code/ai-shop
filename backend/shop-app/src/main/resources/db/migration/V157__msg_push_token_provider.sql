-- App 推送设备加「供应商」维度（设计：多渠道推送与运营端触达配置 · 需求 2）。
--
-- 此前 msg_push_token 只认个推：client_id 注释写死「个推 cid」，没有一列说明
-- 这台设备该由哪家供应商推。要支持 Google FCM（海外 Android）、Apple APNs（iOS 直连），
-- 就得先能区分设备归属，PushRouter 才有依据分发。
--
-- provider 默认 GETUI：存量设备与 uni-push 打包上报的都是个推 cid，一列补上不改行为。
ALTER TABLE msg_push_token
    ADD COLUMN provider VARCHAR(16) NOT NULL DEFAULT 'GETUI'
        COMMENT '推送供应商 GETUI / FCM / APNS' AFTER platform;

-- 唯一键扩到含 provider：同一台 iOS 可能既有个推 cid 又有 APNs token，是两条设备令牌，
-- 各推各的。原键 (receiver_type, receiver_no, platform) 会把它们挤成一条。
ALTER TABLE msg_push_token DROP INDEX uk_push_token_receiver;
ALTER TABLE msg_push_token
    ADD UNIQUE KEY uk_push_token_receiver (receiver_type, receiver_no, platform, provider);
