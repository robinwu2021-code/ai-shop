package ai.neargo.shop.pay.openapp;

import ai.neargo.shop.pay.openapp.entity.PayApp;
import java.util.Optional;

/**
 * 平台用接入方的密钥签出站报文（推送；之后的响应签名同此，开发计划 M3.4）：用当前版本（ACTIVE），
 * 与入站验签同一把、同一种算法 —— 接入方拿自己的 AppSecret 就能验。
 */
public interface AppSigner {

    /** 接入方没有可用密钥（未发、已吊销、密钥服务不可用）时为空 */
    Optional<Signature> sign(PayApp app, byte[] data);

    /**
     * @param keyVersion 签名用的密钥版本；部署配置里的密钥没有版本，为空
     * @param keySerial  该版本的 KeySerial（响应头 {@code X-Key-Serial}）；没有为空
     */
    record Signature(String base64, Integer keyVersion, String keySerial) {
    }
}
