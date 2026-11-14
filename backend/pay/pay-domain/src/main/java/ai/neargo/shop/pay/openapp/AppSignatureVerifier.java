package ai.neargo.shop.pay.openapp;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.Optional;

/**
 * 接入方请求签名的验证（开发计划 M3.1；类清单 §3，取代 {@code AppKeyResolver}）：不再把密钥材料交给调用方，
 * 只回答「这个签名对不对、是哪一版密钥签的」—— 材料留在密钥模块里，L5（KMS / HSM）的 AppSecret 也能验。
 *
 * <p>HMAC 密钥是 AppSecret 去掉展示前缀（{@code sk_live_} / {@code sk_test_}）的那段（API 清单 §7 P1）。
 * 比较一律常量时间；任何内部失败都是「验不过」，不抛、不透原因。
 */
public interface AppSignatureVerifier {

    /**
     * @param keySerial 请求头 {@code X-Key-Serial}：给了就只用那一版；为空则逐把试 ACTIVE 与重叠期内的 ROTATING
     * @param signature 请求头 {@code X-Signature}（Base64）
     */
    Optional<Verified> verify(PayApp app, String keySerial, String toSign, String signature);

    /** @param keyVersion 签名用的密钥版本（{@code pay_api_request_log.sign_key_version}）；部署配置里的密钥没有版本，为空 */
    record Verified(Integer keyVersion) {
    }
}
