package ai.neargo.shop.pay.openapp.service;

/**
 * 接入方密钥的发放（TDD-支付服务-开放API加解密与密钥分发 §2.1 / §2.3 · 类清单 §4.1）：
 * 平台生成 AppSecret（签名）与 EncryptKey（加密），存进平台密钥模块（{@code sec_key}，数据库设计 §2）。
 *
 * <p>新版本是 DRAFT：明文只在这一次返回里出现，启用走密钥模块的生命周期（测试调用通过 → 第二人启用）。
 * 谁能调它（开发者控制台的 OWNER，运营不能）由调用方的入口决定，这里只认接入方。
 */
public interface PartnerKeyService {

    /** 生成（或重置出新版本）AppSecret：{@code sk_live_} / {@code sk_test_} + 32 位字母数字 */
    IssuedCredential issueAppSecret(String appNo, String issuedBy);

    /** 生成（或重置出新版本）EncryptKey：32 位字母数字，不加前缀 */
    IssuedCredential issueEncryptKey(String appNo, String issuedBy);

    /**
     * 一次发放的结果。
     *
     * @param keySerial   KeySerial（请求头 {@code X-Key-Serial} / {@code X-Encrypt-Serial} 传的就是它）
     * @param secret      交给接入方的整串（AppSecret 含前缀）；<b>只此一次，不得落库、不得进日志</b>
     * @param fingerprint 可回显的指纹（AppSecret 带前缀便于认环境：{@code sk_live_…3f9a1c2e}）
     */
    record IssuedCredential(String keySerial, int version, String secret, String fingerprint) {

        @Override
        public String toString() {
            return "IssuedCredential[serial=" + keySerial + ", version=" + version + ", fingerprint=" + fingerprint + "]";
        }
    }
}
