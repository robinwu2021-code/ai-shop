package ai.neargo.shop.pay.openapp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 签名 v1 的固定向量（安全规范 §2.3）。期望值由 Python {@code hashlib / hmac / urllib.parse.quote(safe='-_.~')}
 * 独立算出，不是拿本实现跑一遍抄回来的 —— 对外文档的示例向量就用这一组，改了规则这里必红。
 */
class SignatureCanonicalizerTest {

    private static final String BODY = "{\"outOrderNo\":\"OUT-1\",\"amountMinor\":1990}";

    @Test
    @DisplayName("★★★ 固定向量：查询串按名、同名按值排序，RFC 3986 编码（空格 %20、* 编码、~ 不编码），HMAC-SHA256 后 Base64")
    void fixedVector() throws Exception {
        Map<String, List<String>> q = new LinkedHashMap<>();
        q.put("b", List.of("2"));
        q.put("a", List.of("x y", "1"));
        q.put("c", List.of("中*~"));
        String bodyHash = SignatureCanonicalizer.sha256Hex(BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(bodyHash).isEqualTo("f334610a1bb21a18710c2fdccfd59bb0a738dfca17ca994bbd893a6e56df0531");
        assertThat(SignatureCanonicalizer.canonicalQuery(q)).isEqualTo("a=1&a=x%20y&b=2&c=%E4%B8%AD%2A~");

        String sts = SignatureCanonicalizer.stringToSign("post", "/open/pay/v1/orders", q, "APP-1", "1767225600000",
                "n0nce-abcdef12", bodyHash);
        assertThat(sts).isEqualTo("POST\n/open/pay/v1/orders\na=1&a=x%20y&b=2&c=%E4%B8%AD%2A~\nAPP-1\n1767225600000\n"
                + "n0nce-abcdef12\nf334610a1bb21a18710c2fdccfd59bb0a738dfca17ca994bbd893a6e56df0531");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("secret-0123456789".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        assertThat(Base64.getEncoder().encodeToString(mac.doFinal(sts.getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("YTLsseXP0FWjy1fDVO9qE18eqTGFZS0MNZ1hcYOi/W4=");
    }

    @Test
    @DisplayName("★★ 没有查询串 / 空体：查询串一行为空串，体摘要是空串的 SHA-256")
    void emptyQueryAndBody() {
        assertThat(SignatureCanonicalizer.canonicalQuery(Map.of())).isEmpty();
        assertThat(SignatureCanonicalizer.sha256Hex(new byte[0]))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    @DisplayName("★★ 解析查询串：同名多值保序收集；%20 是空格，+ 是字面加号；只有名没有值记空串")
    void parseQuery() {
        Map<String, List<String>> q = SignatureCanonicalizer.parseQuery("a=x%20y&a=1&b=1+1&flag");
        assertThat(q.get("a")).containsExactly("x y", "1");
        assertThat(q.get("b")).containsExactly("1+1");
        assertThat(q.get("flag")).containsExactly("");
        assertThat(SignatureCanonicalizer.canonicalQuery(q)).isEqualTo("a=1&a=x%20y&b=1%2B1&flag=");
    }
}
