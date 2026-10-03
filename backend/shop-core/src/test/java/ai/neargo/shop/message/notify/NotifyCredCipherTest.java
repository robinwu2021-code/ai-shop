package ai.neargo.shop.message.notify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商家渠道凭据加密（设计：触达推送中台 · N5）。没有真实链路，这里就是证明
 * 「密钥能加解密、篡改能发现、缺钥即拒」的唯一地方。
 */
@DisplayName("商家渠道凭据加密")
class NotifyCredCipherTest {

    /** 32 字节 AES-256 密钥，测试固定值。 */
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    @DisplayName("★★★ 加解密往返：明文进、密文出、解回原文")
    void roundTrip() {
        NotifyCredCipher cipher = new NotifyCredCipher(KEY);
        String plain = "{\"appKey\":\"abc\",\"masterSecret\":\"s3cr3t\"}";
        String enc = cipher.encrypt(plain);
        assertThat(enc).as("密文不能等于明文").isNotEqualTo(plain);
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    @DisplayName("★★ 同明文两次加密密文不同（IV 每条随机）")
    void ivIsRandomPerEncrypt() {
        NotifyCredCipher cipher = new NotifyCredCipher(KEY);
        assertThat(cipher.encrypt("same")).isNotEqualTo(cipher.encrypt("same"));
    }

    @Test
    @DisplayName("★★★ 密文被改一个字节 → 解密抛，不给错误明文（GCM 完整性）")
    void tamperIsDetected() {
        NotifyCredCipher cipher = new NotifyCredCipher(KEY);
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("payload"));
        raw[raw.length - 1] ^= 0x01; // 翻转最后一位（tag 里）
        String tampered = Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★★★ 未配密钥：加解密都抛，绝不明文落库")
    void missingKeyFailsClosed() {
        NotifyCredCipher noKey = new NotifyCredCipher("");
        assertThat(noKey.configured()).isFalse();
        assertThatThrownBy(() -> noKey.encrypt("x")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> noKey.decrypt("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("★ 密钥长度不对：构造即拒（不是 16/24/32 字节）")
    void badKeyLengthRejected() {
        assertThatThrownBy(() -> new NotifyCredCipher(Base64.getEncoder().encodeToString(new byte[20])))
                .isInstanceOf(IllegalStateException.class);
    }
}
