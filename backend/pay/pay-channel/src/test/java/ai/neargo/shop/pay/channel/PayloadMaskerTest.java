package ai.neargo.shop.pay.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
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

    // ---------------------------------------------------------------- W2 · R30 递归脱敏

    @Test
    @DisplayName("★★★ R30 嵌套 Map 里的敏感键也遮 —— 微信回调的 nonce 在 resource 里，不在第一层")
    void nestedMapIsMasked() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("out_trade_no", "OD-1");
        body.put("resource", Map.of("ciphertext", "CIPHER", "nonce", "NONCE-1", "associated_data", "transaction"));
        body.put("payer", Map.of("openid", "oABC"));

        String masked = PayloadMasker.mask(body);

        assertThat(masked).doesNotContain("NONCE-1");
        // 不是把整个 resource 遮掉：同层的非敏感字段要留着，排查要看 associated_data
        assertThat(masked).contains("transaction").contains("oABC").contains("out_trade_no=OD-1");
    }

    @Test
    @DisplayName("★★★ R30 List 里的 Map 也遮 —— 分账接收方是一组，每一项都可能带签名")
    void listOfMapsIsMasked() {
        Map<String, Object> body = Map.of("receivers", List.of(
                Map.of("account", "ACC-1", "sign", "SIG-1"),
                Map.of("account", "ACC-2", "partner_key", "KEY-2")));

        String masked = PayloadMasker.mask(body);

        assertThat(masked).doesNotContain("SIG-1").doesNotContain("KEY-2");
        assertThat(masked).contains("ACC-1").contains("ACC-2");
    }

    @Test
    @DisplayName("★★★ R30 值是 JSON 字符串的，先解析再遮 —— 蓝新把 TradeSha 放在一段字符串化的 JSON 里")
    void jsonStringValueIsMasked() {
        Map<String, Object> body = Map.of("Result", "{\"TradeSha\":\"SHA-SECRET\",\"Amt\":1200,\"CheckCode\":\"CC-SECRET\"}");

        String masked = PayloadMasker.mask(body);

        assertThat(masked).doesNotContain("SHA-SECRET").doesNotContain("CC-SECRET");
        assertThat(masked).contains("1200");
    }

    @Test
    @DisplayName("★★★ R30 蓝新 TradeSha / CheckCode / CheckValue、LINE Pay ChannelSecret、时间戳都算敏感")
    void newChannelKeysAreSensitive() {
        assertThat(PayloadMasker.sensitive("TradeSha")).isTrue();
        assertThat(PayloadMasker.sensitive("CheckCode")).isTrue();
        assertThat(PayloadMasker.sensitive("CheckValue")).isTrue();
        assertThat(PayloadMasker.sensitive("X-LINE-ChannelSecret")).isTrue();
        assertThat(PayloadMasker.sensitive("ChannelSecret")).isTrue();
        // 注释一直说遮 timestamp，代码此前没有（R30）
        assertThat(PayloadMasker.sensitive("Wechatpay-Timestamp")).isTrue();
        assertThat(PayloadMasker.sensitive("timeStamp")).isTrue();
        // 控制量
        assertThat(PayloadMasker.sensitive("TradeNo")).isFalse();
        assertThat(PayloadMasker.sensitive("MerchantOrderNo")).isFalse();
    }

    @Test
    @DisplayName("★★ 整段报文文本：JSON 递归遮、表单按参数遮、都不是就只留指纹")
    void textBodies() {
        String json = PayloadMasker.maskText("{\"a\":{\"b\":[{\"sign\":\"S\",\"c\":\"C\"}]},\"nonce_str\":\"N\"}");
        assertThat(json).doesNotContain("\"S\"").doesNotContain("\"N\"").contains("\"C\"");
        assertThat(json).startsWith("{");

        String form = PayloadMasker.maskText("app_id=2021&sign=SIGNED&biz_content=%7B%22out_trade_no%22%3A%22OD-1%22%7D");
        assertThat(form).doesNotContain("SIGNED").contains("app_id=2021").contains("OD-1");

        String opaque = PayloadMasker.maskText("<xml>whatever</xml>");
        assertThat(opaque).contains("sha256=").doesNotContain("whatever");
        assertThat(PayloadMasker.maskText(null)).isNull();
        assertThat(PayloadMasker.maskText("")).isEmpty();
    }
}
