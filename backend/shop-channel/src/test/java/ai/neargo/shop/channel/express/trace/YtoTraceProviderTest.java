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

    /** 取值全集照官方返回参数表的 infoContent 一列。TMS_RETURN 是真·退回码（不是 RETURN/REJECT/RETENTION）。 */
    @Test
    void mapStatus_coversOfficialCodes() {
        assertThat(YtoTraceProvider.mapStatus("GOT")).isEqualTo(TraceStatus.PICKED);
        assertThat(YtoTraceProvider.mapStatus("SIGNED")).isEqualTo(TraceStatus.SIGNED);
        assertThat(YtoTraceProvider.mapStatus("FAILED")).isEqualTo(TraceStatus.EXCEPTION);
        // ★ 退回必须是异常。写成 RETURN 时它会掉进 default 被当成「运输中」——
        //   单子退回去了，买家看到的还是运输中，且不报错
        assertThat(YtoTraceProvider.mapStatus("TMS_RETURN")).isEqualTo(TraceStatus.EXCEPTION);
        for (String inTransit : new String[]{"ARRIVAL", "DEPARTURE", "SENT_SCAN", "INBOUND",
                                             "FORWARDING", "AIRSEND", "AIRPICK"}) {
            assertThat(YtoTraceProvider.mapStatus(inTransit)).as(inTransit).isEqualTo(TraceStatus.IN_TRANSIT);
        }
        assertThat(YtoTraceProvider.mapStatus(null)).isEqualTo(TraceStatus.UNKNOWN);
    }

    /**
     * ★ 真实返回结构：<b>顶层就是数组</b>，且按时间<b>正序</b>（最早在前）。
     * 样例逐字取自官方「物流轨迹查询接口」的【成功返回格式-json】。
     */
    @Test
    void parse_officialArrayShape_newestFirst() {
        String body = """
            [
              {"waybill_No":"YT2000000000000","upload_Time":"2023-04-24 20:37:33","infoContent":"GOT",
               "processInfo":"您的快件被【浙江省金华市义乌市上溪镇】揽收","city":"金华市","district":"义乌市","weight":0.68},
              {"waybill_No":"YT2000000000000","upload_Time":"2023-04-25 01:45:03","infoContent":"ARRIVAL",
               "processInfo":"您的快件已经到达【义乌转运中心直营公司】","city":"金华市","district":"义乌市"},
              {"waybill_No":"YT2000000000000","upload_Time":"2023-04-25 15:09:52","infoContent":"SIGNED",
               "processInfo":"您的快件已签收，签收人: xxx","city":"绍兴市","district":"上虞区"}
            ]
            """;
        TraceResult r = YtoTraceProvider.parse("YT2000000000000", "YTO", json.readTree(body));

        assertThat(r.provider()).isEqualTo("yto");
        assertThat(r.carrier()).isEqualTo("YTO");
        assertThat(r.nodes()).hasSize(3);
        // 圆通给正序，端上要倒序：签收(15:09) → 到达(01:45) → 揽收(前一天 20:37)
        assertThat(r.nodes().get(0).status()).isEqualTo(TraceStatus.SIGNED);
        assertThat(r.nodes().get(1).status()).isEqualTo(TraceStatus.IN_TRANSIT);
        assertThat(r.nodes().get(2).status()).isEqualTo(TraceStatus.PICKED);
        // 整单状态取最新那条 —— 顺序搞反就会永远停在「已揽收」
        assertThat(r.status()).isEqualTo(TraceStatus.SIGNED);
        // 文案用 processInfo（圆通给的人话，与官网一致），位置用 city
        assertThat(r.nodes().get(0).info()).isEqualTo("您的快件已签收，签收人: xxx");
        assertThat(r.nodes().get(0).location()).isEqualTo("绍兴市");
        assertThat(r.nodes().get(2).at()).as("upload_Time 要真解出时刻").isPositive();
    }

    /**
     * ★ 查不到时圆通换了一种结构（对象、不是数组，success 还是字符串）——
     * 只认成功那种的话，这里会静默解成空而不报错，排查时看不出是「没查到」还是「解析错了」。
     */
    @Test
    void parse_emptyResultShape_isUnknown() {
        String body = """
            {"map":{"YT2600205450611":[]},"code":"1001","success":"true","message":"查询结果为空。"}
            """;
        TraceResult r = YtoTraceProvider.parse("YT2600205450611", "YTO", json.readTree(body));
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
    void post_sendsOfficialJsonBodyWithRealSignature() throws Exception {
        var received = new java.util.concurrent.ArrayBlockingQueue<String[]>(1);
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.offer(new String[]{body, String.valueOf(ex.getRequestHeaders().getFirst("Content-Type"))});
            // 官方结构：顶层数组、正序（最早在前）
            byte[] resp = ("""
                    [
                      {"waybill_No":"YT123","upload_Time":"2026-10-07 18:00:00","infoContent":"GOT",
                       "processInfo":"您的快件被【深圳市】揽收","city":"深圳市","district":"南山区"},
                      {"waybill_No":"YT123","upload_Time":"2026-10-08 09:30:00","infoContent":"SIGNED",
                       "processInfo":"您的快件已签收","city":"深圳市","district":"南山区"}
                    ]""").getBytes(StandardCharsets.UTF_8);
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
            // 报文形状对齐圆通平台自己生成的那一份（在线调试里取到的真请求）
            assertThat(got[1]).as("body 是 JSON，不是表单").isEqualTo("application/json");

            var sent = json.readTree(got[0]);
            String param = "{\"NUMBER\":\"YT123\"}";
            assertThat(sent.path("format").asString("")).isEqualTo("JSON");
            assertThat(sent.path("param").asString("")).as("一次一个单号，键是大写 NUMBER").isEqualTo(param);
            assertThat(sent.path("timestamp").asString("")).matches("\\d{13}");
            // ★ 真正的判据：发到线上的签名 = 按真算法算出来的那一个（密钥不进报文）
            assertThat(sent.path("sign").asString(""))
                    .isEqualTo(YtoTraceProvider.sign(param, "TRACE_QUERY", "1.0", "sk-test"));

            // ★ 只有这四个字段。method/v/appKey 在 URL 路径里，不进 body ——
            //   多发了圆通不会提示，只会在验签那一步回「请求加密校验失败」，而人会先去怀疑密钥
            var keys = new java.util.ArrayList<String>();
            sent.propertyNames().forEach(keys::add);
            assertThat(keys).containsExactlyInAnyOrder("timestamp", "param", "sign", "format");
            assertThat(got[0]).as("客户密钥绝不能出现在报文里").doesNotContain("sk-test");

            // 响应按官方结构解回来（最新在前）
            assertThat(r.status()).isEqualTo(TraceStatus.SIGNED);
            assertThat(r.nodes()).hasSize(2);
            assertThat(r.nodes().get(0).info()).isEqualTo("您的快件已签收");
            assertThat(r.nodes().get(0).location()).isEqualTo("深圳市");
            assertThat(r.nodes().get(0).at()).isPositive();
        } finally {
            server.stop(0);
        }
    }
}
