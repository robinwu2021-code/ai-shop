package ai.neargo.shop.channel.express.trace;

import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** 圆通 provider 的可测部分：签名、状态映射、响应解析（TDD-圆通物流直连 Y2）。 */
class YtoTraceProviderTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sign_isBase64OfRawMd5_notHex() throws Exception {
        String param = "{\"Number\":\"YT123\"}";
        String got = YtoTraceProvider.sign(param, "TRACE_QUERY", "1.0", "secret-x");

        // 手算一遍：Base64(MD5bytes(param+method+v+secret))
        byte[] md5 = MessageDigest.getInstance("MD5")
                .digest((param + "TRACE_QUERY" + "1.0" + "secret-x").getBytes(StandardCharsets.UTF_8));
        assertThat(got).isEqualTo(Base64.getEncoder().encodeToString(md5));

        // 是 Base64 不是十六进制：解出来必须是 16 字节的原始摘要
        assertThat(Base64.getDecoder().decode(got)).hasSize(16);
        // 十六进制写法应为 32 个 [0-9a-f]，这里不该是那样
        assertThat(got).doesNotMatch("^[0-9a-fA-F]{32}$");
    }

    @Test
    void sign_isDeterministic() {
        String a = YtoTraceProvider.sign("{\"Number\":\"A\"}", "M", "1.0", "k");
        String b = YtoTraceProvider.sign("{\"Number\":\"A\"}", "M", "1.0", "k");
        assertThat(a).isEqualTo(b);
        // 单号变了签名要变
        assertThat(YtoTraceProvider.sign("{\"Number\":\"B\"}", "M", "1.0", "k")).isNotEqualTo(a);
    }

    @Test
    void mapStatus_coversKnownCodes() {
        assertThat(YtoTraceProvider.mapStatus("GOT")).isEqualTo(TraceStatus.PICKED);
        assertThat(YtoTraceProvider.mapStatus("SIGNED")).isEqualTo(TraceStatus.SIGNED);
        assertThat(YtoTraceProvider.mapStatus("RETURN")).isEqualTo(TraceStatus.EXCEPTION);
        assertThat(YtoTraceProvider.mapStatus("DEPARTURE")).isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(YtoTraceProvider.mapStatus("ARRIVAL")).isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(YtoTraceProvider.mapStatus(null)).isEqualTo(TraceStatus.UNKNOWN);
    }

    @Test
    void parse_ordersNewestFirst_andStatusIsLatest() {
        String body = """
            {"result":{"traces":[
              {"opCode":"GOT","opTime":"2026-10-01 10:00:00","opName":"已揽收","city":"深圳市"},
              {"opCode":"SIGNED","opTime":"2026-10-03 14:00:00","opName":"已签收","city":"杭州市"},
              {"opCode":"ARRIVAL","opTime":"2026-10-02 08:00:00","opName":"到达","city":"杭州转运中心"}
            ]}}
            """;
        TraceResult r = YtoTraceProvider.parse("YT1", "YTO", json.readTree(body));

        assertThat(r.provider()).isEqualTo("yto");
        assertThat(r.carrier()).isEqualTo("YTO");
        assertThat(r.nodes()).hasSize(3);
        // 最新在前：签收(10-03) → 到达(10-02) → 揽收(10-01)
        assertThat(r.nodes().get(0).status()).isEqualTo(TraceStatus.SIGNED);
        assertThat(r.nodes().get(1).status()).isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(r.nodes().get(2).status()).isEqualTo(TraceStatus.PICKED);
        // 整单状态取最新那条
        assertThat(r.status()).isEqualTo(TraceStatus.SIGNED);
        assertThat(r.nodes().get(0).location()).isEqualTo("杭州市");
    }

    @Test
    void parse_emptyTraces_isUnknown() {
        TraceResult r = YtoTraceProvider.parse("YT2", "YTO", json.readTree("{\"result\":{\"traces\":[]}}"));
        assertThat(r.status()).isEqualTo(TraceStatus.UNKNOWN);
        assertThat(r.nodes()).isEmpty();
    }
}
