-- 三竹渠道登记沿用 V158 的 enabled=1 口径，不切换实际发送通道。
-- 实际装配仍由 shop.sms.stub/provider 决定，默认保持桩；凭据不存表。
INSERT INTO notify_channel (channel_no, channel_type, provider, scope, owner_no,
                            cred_ref, enabled, created_at, updated_at)
SELECT 'NCH-SMS-MITAKE', 'SMS', 'MITAKE', 'PLATFORM', '',
       'shop.sms.mitake', 1, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM notify_channel
    WHERE channel_type = 'SMS' AND provider = 'MITAKE'
      AND scope = 'PLATFORM' AND owner_no = ''
);
