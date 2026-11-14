package ai.neargo.shop.spi.pay;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code X-Call-Source} 头（TDD-支付服务-接入守卫模块pay-guard §2.3 A；开发计划 M2.1）：
 * Base64URL 的 JSON。它只用于留痕 —— 所以编不出来要截断而不是失败，解不出来要给空而不是抛。
 */
class CallSourceCodecTest {

    private static final CallSource FULL = new CallSource("shop-app", "MALL", "api-01", "10.0.1.12", "req-12345678",
            "POST /mp/orders/{orderNo}/pay", "APP_C", "223.104.1.1", "ab".repeat(32), "U-1001",
            "2026-10-28T08:00:00Z");

    @Test
    @DisplayName("★★★ 编完再解，每个字段原样回来")
    void roundTrip() {
        assertThat(CallSourceCodec.decode(CallSourceCodec.encode(FULL))).contains(FULL);
    }

    @Test
    @DisplayName("★★ 编出来的是 URL 安全、不带填充的 Base64 —— 能原样放进 HTTP 头")
    void headerSafe() {
        assertThat(CallSourceCodec.encode(FULL)).matches("[A-Za-z0-9_-]+");
    }

    @Test
    @DisplayName("★★★ 超长字段按落库的列宽截断；全部字段都超长，整个头也不超过 4KB")
    void overlongFieldsAreTruncated() {
        String huge = "x".repeat(10_000);
        CallSource big = new CallSource(huge, huge, huge, huge, huge, huge, huge, huge, huge, huge, huge);

        String header = CallSourceCodec.encode(big);
        CallSource back = CallSourceCodec.decode(header).orElseThrow();

        assertThat(header.length()).isLessThanOrEqualTo(CallSourceCodec.MAX_HEADER);
        assertThat(back.service()).hasSize(64);
        assertThat(back.module()).hasSize(24);
        assertThat(back.instance()).hasSize(128);
        assertThat(back.originApi()).hasSize(128);
        assertThat(back.originClient()).hasSize(24);
        assertThat(back.originUaDigest()).hasSize(64);
        assertThat(back.traceId()).hasSize(64);
    }

    @Test
    @DisplayName("★★ 空字段不出现在 JSON 里，解回来仍是空")
    void nullsSurvive() {
        CallSource sparse = new CallSource("shop-app", null, null, null, "t-1", null, null, null, null, null, null);
        assertThat(CallSourceCodec.decode(CallSourceCodec.encode(sparse))).contains(sparse);
    }

    @Test
    @DisplayName("★★★ 缺失、乱码、不是 JSON、超过 4KB —— 一律解成空，不抛（照样放行，记 UNKNOWN）")
    void brokenHeadersDecodeToEmpty() {
        assertThat(CallSourceCodec.decode(null)).isEmpty();
        assertThat(CallSourceCodec.decode("  ")).isEmpty();
        assertThat(CallSourceCodec.decode("%%%not-base64%%%")).isEmpty();
        assertThat(CallSourceCodec.decode(b64("not json"))).isEmpty();
        assertThat(CallSourceCodec.decode(b64("[1,2,3]"))).isEmpty();
        assertThat(CallSourceCodec.decode("A".repeat(CallSourceCodec.MAX_HEADER + 1))).isEmpty();
    }

    @Test
    @DisplayName("★★ 认不出的字段忽略：调用方先升级、支付后升级时照样解得开")
    void unknownFieldsIgnored() {
        assertThat(CallSourceCodec.decode(b64("{\"service\":\"food-svc\",\"newField\":1}")))
                .hasValueSatisfying(s -> assertThat(s.service()).isEqualTo("food-svc"));
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
