package ai.neargo.shop.trade.track;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 看件令牌的几条承诺（TDD-收件人物流触达 §4）：签得出、验得回、改一个字节就失效、过期就失效。
 */
@DisplayName("看件令牌 ShipTrackToken")
class ShipTrackTokenTest {

    private final ShipTrackToken token = new ShipTrackToken("unit-test-key-abc", 30);

    @Test
    @DisplayName("签发→验回，拿回的是原来的子单号")
    void signThenVerify() {
        String t = token.sign("SUB250101ABCDEF");
        assertThat(token.verify(t)).contains("SUB250101ABCDEF");
    }

    @Test
    @DisplayName("★ 改签名里的字节都验不过 —— 别人伪造不出来")
    void tamperedTokenRejected() {
        String t = token.sign("SUB-A");
        /*
         * 翻签名的**第一个** base64 字符，而不是最后一个。
         *
         * HMAC-SHA256 是 32 字节 = 43 个 base64url 字符，**末位只承载 2 个有效 bit、另 4 bit 未用**，
         * 而 JDK 的 url 解码器对尾部未用 bit 是宽松的 —— 翻末位有时落进同一等价类、decode 回同样的字节，
         * 验签照过（base64 可变性）。`sign` 的 payload 含随时间变的 exp，于是末位是否可变随机，
         * 这条断言便成了时间相关的偶发假阴性。签名首字符 6 bit 全用，任何翻转必改变字节，恒被拒。
         */
        int dot = t.indexOf('.');
        char first = t.charAt(dot + 1);
        String flipped = t.substring(0, dot + 1) + (first == 'A' ? 'B' : 'A') + t.substring(dot + 2);
        assertThat(token.verify(flipped)).isEmpty();
    }

    @Test
    @DisplayName("★ 换个密钥签的票，这边验不过 —— 密钥是唯一的信任根")
    void otherKeyRejected() {
        ShipTrackToken other = new ShipTrackToken("a-totally-different-key", 30);
        assertThat(token.verify(other.sign("SUB-A"))).isEmpty();
    }

    @Test
    @DisplayName("★ 过期即失效 —— exp 在过去的票验不过")
    void expiredRejected() {
        long oneHourAgo = System.currentTimeMillis() / 1000 - 3600;
        String stale = token.signWithExp("SUB-A", oneHourAgo);
        assertThat(token.verify(stale)).as("过期票应失效").isEmpty();

        long later = System.currentTimeMillis() / 1000 + 3600;
        String fresh = token.signWithExp("SUB-A", later);
        assertThat(token.verify(fresh)).as("未过期的票仍有效").contains("SUB-A");
    }

    @Test
    @DisplayName("格式不对的串一律空：无点、空串、乱码")
    void malformedRejected() {
        assertThat(token.verify(null)).isEmpty();
        assertThat(token.verify("")).isEmpty();
        assertThat(token.verify("no-dot-here")).isEmpty();
        assertThat(token.verify(".")).isEmpty();
        assertThat(token.verify("abc.")).isEmpty();
        assertThat(token.verify(".def")).isEmpty();
        assertThat(token.verify("!!!.@@@")).isEmpty();
    }

    @Test
    @DisplayName("子单号里带分隔符|也能原样取回 —— 用 lastIndexOf 切 exp")
    void subOrderWithBarSurvives() {
        // 现实里子单号不含 |，但实现用 lastIndexOf 切，带了也不该错
        String t = token.sign("SUB|WEIRD");
        Optional<String> got = token.verify(t);
        assertThat(got).contains("SUB|WEIRD");
    }
}
