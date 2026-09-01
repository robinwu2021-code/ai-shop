package ai.neargo.shop.pay.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 报文落库前的脱敏。
 *
 * <p><b>这组用例的方向是「宁可多遮」</b>：遮错了顶多让排查少一条线索，
 * 漏遮一次是凭据出现在备份、导出的 CSV、以及粘进工单的那段文本里。
 */
class PayloadMaskerTest {

    @Test
    @DisplayName("★★★ 键名带 sign / key / secret 的一律遮掉，大小写不敏感")
    void sensitiveKeysAreMasked() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", "OD-1");
        body.put("sign", "SIG");
        body.put("Wechatpay-Signature", "SIG2");
        body.put("mch_key", "KEY");
        body.put("app_secret", "SEC");
        body.put("Authorization", "Bearer x");

        String masked = PayloadMasker.mask(body);

        assertThat(masked).contains("out_trade_no=OD-1");
        assertThat(masked).doesNotContain("SIG").doesNotContain("SIG2")
                .doesNotContain("KEY").doesNotContain("SEC").doesNotContain("Bearer");
        // 键名要留着 —— 「有这个字段但遮了」与「根本没这个字段」是两件事
        assertThat(masked).contains("sign=***").contains("Authorization=***");
    }

    @Test
    @DisplayName("★★ nonce / timestamp 也遮 —— 单独看无害，与签名凑一起就是可重放的一组")
    void replayMaterialIsMasked() {
        assertThat(PayloadMasker.sensitive("nonce_str")).isTrue();
        assertThat(PayloadMasker.sensitive("serial_no")).isTrue();
        assertThat(PayloadMasker.sensitive("private_key")).isTrue();
        // 反向控制量：普通业务字段不能被误伤，否则这张表就没什么可看的了
        assertThat(PayloadMasker.sensitive("out_trade_no")).isFalse();
        assertThat(PayloadMasker.sensitive("amount")).isFalse();
        assertThat(PayloadMasker.sensitive("transaction_id")).isFalse();
    }

    @Test
    @DisplayName("★★ 没验过签的报文只留指纹与前缀 —— 端点公网可写，原样落库就是个写入口")
    void unverifiedKeepsOnlyFingerprintAndHead() {
        String body = "X".repeat(5000);

        String stored = PayloadMasker.unverified(body);

        assertThat(stored).contains("sha256=").contains("length=5000");
        assertThat(stored.length()).isLessThan(PayloadMasker.UNVERIFIED_LEN + 200);
        // 同一份两次，指纹一样 —— 排查时靠它分「通道在重推同一份」与「有人在扫端点」
        assertThat(PayloadMasker.fingerprint(body)).isEqualTo(PayloadMasker.fingerprint(body));
        assertThat(PayloadMasker.fingerprint(body)).isNotEqualTo(PayloadMasker.fingerprint(body + "!"));
    }

    @Test
    @DisplayName("★ 超长报文截断并说明原长 —— 不说的话读的人会以为通道就推了这么多")
    void longPayloadIsTruncatedWithNotice() {
        String masked = PayloadMasker.mask(Map.of("data", "Y".repeat(PayloadMasker.MAX_LEN + 100)));

        assertThat(masked).hasSizeLessThan(PayloadMasker.MAX_LEN + 100);
        assertThat(masked).contains("已截断").contains("原长");
    }

    @Test
    @DisplayName("★ 空 map 存 null 而不是空串 —— 「没有报文」与「报文是空的」要分得开")
    void emptyIsNull() {
        assertThat(PayloadMasker.mask(Map.of())).isNull();
        assertThat(PayloadMasker.mask(null)).isNull();
        assertThat(PayloadMasker.unverified(null)).isNull();
    }
}
