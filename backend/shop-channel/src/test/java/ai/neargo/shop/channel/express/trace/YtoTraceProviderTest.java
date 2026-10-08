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

    /**
     * ★ 线格式：真的发一次请求，断言**线上收到的是什么**。
     *
     * <p>此前只测到 {@link YtoTraceProvider#sign} 这个纯函数，而 {@code post()} 一条都没有 ——
     * 表单编码、Content-Type、以及「算出来的签名有没有真的发出去」全是盲区。
     * 这几样错了，单测照样全绿，等拿到真凭据联调时才会以「圆通说签名不对」的形式出现，
     * 而那时你会先去怀疑密钥和白名单，查不到这一层。
     *
     * <p>用 JDK 自带的 HttpServer 当假圆通：provider 是真的，HTTP 是真的，只有对端是假的。
     */
    @Test
    void post_sendsFormWithRealSignature_andParsesResponse() throws Exception {
        var received = new java.util.concurrent.ArrayBlockingQueue<String[]>(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.offer(new String[]{body, String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"))});
            byte[] resp = ("""
                    {"success":true,"result":{"traces":[
                      {"opCode":"SIGNED","opTime":"2026-10-08 09:30:00","opName":"【深圳市】已签收","city":"深圳市"},
                      {"opCode":"GOT","opTime":"2026-10-07 18:00:00","opName":"【深圳市】已揽收","city":"深圳市"}
                    ]}}""").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (var os = ex.getResponseBody()) {
                os.write(resp);
            }
        });
        server.start();
        try {
            var provider = new YtoTraceProvider("ak-test", "sk-test",
                    "http://127.0.0.1:" + server.getAddress().getPort(), "TRACE_QUERY", "1.0");
            assertThat(provider.available()).as("凭据齐了才会真发").isTrue();

            TraceResult r = provider.trace("YTO", "YT123").orElseThrow(
                    () -> new AssertionError("请求没发出去或响应没解析 —— provider 把异常吞成了 empty"));

            String[] got = received.poll(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(got).as("假圆通没收到请求").isNotNull();
            assertThat(got[1]).isEqualTo("application/x-www-form-urlencoded");

            // 把表单拆开逐项比（值是 URLEncoder 编过的，比之前先解回来）
            var form = new java.util.HashMap<String, String>();
            for (String kv : got[0].split("&")) {
                int i = kv.indexOf('=');
                form.put(kv.substring(0, i),
                        java.net.URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8));
            }
            String param = "{\"Number\":\"YT123\"}";
            assertThat(form.get("method")).isEqualTo("TRACE_QUERY");
            assertThat(form.get("v")).isEqualTo("1.0");
            assertThat(form.get("appKey")).isEqualTo("ak-test");
            assertThat(form.get("format")).isEqualTo("JSON");
            assertThat(form.get("param")).as("一次一个单号，param 形如 {\"Number\":\"…\"}").isEqualTo(param);
            assertThat(form.get("timestamp")).matches("\\d{13}");
            // ★ 真正的判据：发到线上的签名 = 按真算法算出来的那一个（密钥不进表单）
            assertThat(form.get("sign")).isEqualTo(YtoTraceProvider.sign(param, "TRACE_QUERY", "1.0", "sk-test"));
            assertThat(got[0]).as("客户密钥绝不能出现在表单里").doesNotContain("sk-test");

            // 响应按文档结构解回来
            assertThat(r.status()).isEqualTo(TraceStatus.SIGNED);
            assertThat(r.nodes()).hasSize(2);
            assertThat(r.nodes().get(0).info()).isEqualTo("【深圳市】已签收");
            assertThat(r.nodes().get(0).location()).isEqualTo("深圳市");
            assertThat(r.nodes().get(0).at()).isPositive();
        } finally {
            server.stop(0);
        }
    }
}
