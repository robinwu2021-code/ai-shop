package ai.neargo.shop.logistics.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneCipherTest {

    @Test
    @DisplayName("★★★ 加密往返；同一号码两次加密结果不同（随机 IV），密文里看不出号码")
    void roundTrip() {
        PhoneCipher c = new PhoneCipher("test-key");
        String a = c.encrypt("13800138000");
        String b = c.encrypt("13800138000");
        assertThat(a).isNotEqualTo(b).doesNotContain("13800138000");
        assertThat(c.decrypt(a)).isEqualTo("13800138000");
    }

    @Test
    @DisplayName("★★ 没配密钥：不存密文（明文永不落库这条不打折）")
    void noKeyStoresNothing() {
        PhoneCipher c = new PhoneCipher("");
        assertThat(c.enabled()).isFalse();
        assertThat(c.encrypt("13800138000")).isNull();
    }

    @Test
    @DisplayName("★ 密钥换过：解不开返回 null，不抛")
    void wrongKeyGivesNull() {
        String enc = new PhoneCipher("k1").encrypt("13800138000");
        assertThat(new PhoneCipher("k2").decrypt(enc)).isNull();
        assertThat(PhoneCipher.last4("13800138000")).isEqualTo("8000");
    }
}
