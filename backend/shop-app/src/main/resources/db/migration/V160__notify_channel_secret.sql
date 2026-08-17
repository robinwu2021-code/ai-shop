-- 商家自带渠道的加密凭据列（设计：触达推送中台-模块抽象 · N5 · 外部接入）。
--
-- scope=MERCHANT 的渠道，商家把自己的短信签名/推送账号密钥交给平台。这些**不能明文落库**，
-- 用 AES-256-GCM 加密存这一列（密钥走 SHOP_NOTIFY_CRED_KEY，见 NotifyCredCipher），
-- 读时解密进内存供发送用、永不回前端。平台接入（PLATFORM）的凭据仍走环境变量，此列为空。
ALTER TABLE notify_channel
    ADD COLUMN secret_cipher VARCHAR(2048) DEFAULT NULL
        COMMENT '商家渠道凭据密文 base64(iv‖密文‖tag)；平台/测试接入为空，明文永不落此列' AFTER config_json;
