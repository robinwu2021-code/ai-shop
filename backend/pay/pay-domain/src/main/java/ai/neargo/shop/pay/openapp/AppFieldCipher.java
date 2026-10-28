package ai.neargo.shop.pay.openapp;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.Optional;

/**
 * 接入方敏感字段的解密（开发计划 M3.5；TDD-支付服务-开放API加解密与密钥分发 §2.4 P3）：
 * AES-256-GCM，密钥 = 接入方 EncryptKey 的 UTF-8 字节，输入 {@code IV(12) ‖ 密文 ‖ Tag(16)}，AAD = {@code AppId|字段路径}。
 * 与签名同一个密钥来源（{@code shop.pay.open.key-source}），所以由同一个类实现。
 */
public interface AppFieldCipher {

    /**
     * @param encryptSerial 请求头 {@code X-Encrypt-Serial}：给了就只用那一版 EncryptKey；为空则逐把试可用的版本
     * @return 明文；版本不认识、标签不符、没有可用密钥 —— 一律为空（不分哪一步）
     */
    Optional<byte[]> decrypt(PayApp app, String encryptSerial, byte[] packed, byte[] aad);

    /**
     * 平台加密给接入方（推送 resource，P4；M3.6）：IV 每次随机。
     *
     * @param version 指定 EncryptKey 版本（重推用冻结的版本）；为空 = 当前版本
     * @return 接入方没有 EncryptKey 时为空
     */
    Optional<Encrypted> encrypt(PayApp app, Integer version, byte[] plaintext, byte[] aad);

    /**
     * @param packed     {@code IV(12) ‖ 密文 ‖ Tag(16)}
     * @param keyVersion EncryptKey 版本；部署配置里的密钥为空
     * @param keySerial  该版本的 KeySerial；没有为空
     */
    record Encrypted(byte[] packed, Integer keyVersion, String keySerial) {
    }
}
