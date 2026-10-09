package ai.neargo.shop.logistics.channel.yto;

import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 圆通直连的轨迹 provider（TDD-圆通物流直连 §3、Y2）。只认 {@code YTO}。
 *
 * <p><b>签名</b>（圆通开放平台）：{@code Base64( MD5bytes( param + method + v + 客户密钥 ) )}。
 * 与快递100 的 {@code MD5 转大写十六进制} 不是一套 —— 圆通是 Base64 的原始摘要字节。
 *
 * <p><b>缺凭据 available()=false，不抛</b>：并存场景里圆通没配不该拖垮别的 provider（见 TraceProvider）。
 * 这与寄件网关「缺凭据启动就失败」不同 —— 那是二选一、开了就必须能用；这里是多选、各管各的。
 *
 * <p><b>响应字段名按圆通文档写，上线前（Y3 拿到真账号）对真实响应校准</b> —— 标在 {@link #parse} 上。
 */
@Component
public class YtoTraceProvider implements TraceProvider {

    private static final Logger log = LoggerFactory.getLogger(YtoTraceProvider.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String appKey;   // 客户编码
    private final String secret;   // 客户密钥
    private final String endpoint;
    private final String method;
    private final String version;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public YtoTraceProvider(
            @Value("${shop.express.yto.app-key:}") String appKey,
            @Value("${shop.express.yto.secret:}") String secret,
            @Value("${shop.express.yto.host:https://openapi.yto.net.cn}") String host,
            // method/v 是圆通给你账号分配的「物流轨迹查询」接口名与版本 —— 拿到账号后按文档填
            @Value("${shop.express.yto.trace-method:TRACE_QUERY}") String method,
            @Value("${shop.express.yto.trace-version:1.0}") String version) {
        this.appKey = appKey == null ? "" : appKey.trim();
        this.secret = secret == null ? "" : secret.trim();
        this.endpoint = host;
        this.method = method;
        this.version = version;
    }

    @Override
    public String name() {
        return "yto";
    }

    @Override
    public boolean covers(String carrier) {
        return "YTO".equals(carrier);
    }

    @Override
    public boolean available() {
        return !appKey.isBlank() && !secret.isBlank();
    }

    @Override
    public Optional<TraceResult> trace(String carrier, String waybillNo) {
        if (!available() || waybillNo == null || waybillNo.isBlank()) {
            return Optional.empty();
        }
        try {
            // 一次一个单号（圆通：param 带 Number）
            String param = json.writeValueAsString(java.util.Map.of("NUMBER", waybillNo));
            String body = post(param);
            return Optional.of(parse(waybillNo, carrier, json.readTree(body)));
        } catch (RuntimeException | java.io.IOException | InterruptedException e) {
            // 吞掉：轨迹拉不到就是「暂无」，不该把订单详情拖垮
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("[yto] 查轨迹失败 waybill={}: {}", waybillNo, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 发一次查询。<b>报文形状对齐了圆通平台自己生成的那一份</b>（2026-10-08 在「在线调试」里取到的真请求）：
     *
     * <pre>Content-Type: application/json
     * {"timestamp":"1791452385901","param":"{\"NUMBER\":\"YT2600227881409\"}",
     *  "sign":"uhSL6hg8txxadZi26YPYPg==","format":"JSON"}</pre>
     *
     * <p>三处与原实现不同，三份证据都指向同一边（官方【报文结构】表、【请求格式-json】示例、平台生成的真请求）：
     * <ol>
     *   <li>body 是 <b>JSON</b>，不是 {@code application/x-www-form-urlencoded}</li>
     *   <li><b>只有四个字段</b>：{@code timestamp/param/sign/format}。
     *       {@code method}、{@code v}、{@code appKey} <b>不进 body</b> ——
     *       它们在 URL 路径里（形如 {@code /open/<method>/<v>/<账号段>/<环境>}），
     *       所以 {@code shop.express.yto.host} 配的是**整条接口地址**，不是一个域名。</li>
     *   <li>{@code param} 的键是<b>大写 {@code NUMBER}</b></li>
     * </ol>
     *
     * <p>⚠️ {@code method}/{@code v} 仍只用于算签名，且<b>取值尚未验证</b>：
     * UAT 环境用各种取值都回 {@code {"code":401,"reason":"请求加密校验失败"}}，
     * 真值要从 控制台→接口管理 的该接口条目上抄。
     */
    private String post(String param) throws java.io.IOException, InterruptedException {
        java.util.Map<String, String> body = new java.util.LinkedHashMap<>();
        body.put("timestamp", String.valueOf(System.currentTimeMillis()));
        body.put("param", param);
        body.put("sign", sign(param, method, version, secret));
        body.put("format", "JSON");
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }

    /** 圆通签名：Base64( MD5bytes( param + method + v + 客户密钥 ) )。**与快递100 的十六进制大写不是一套** */
    static String sign(String param, String method, String v, String secret) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5")
                    .digest((param + method + v + secret).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(md5);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // MD5 一定在
        }
    }

    /**
     * 圆通状态码 → 统一状态。取值取自官方「物流轨迹查询接口」返回参数表的 {@code infoContent} 一列：
     * GOT 已揽收 / ARRIVAL 已收入 / DEPARTURE 已发出 / SENT_SCAN 派件 / INBOUND 自提柜入柜 /
     * SIGNED 签收成功 / FAILED 签收失败 / FORWARDING 转寄 / TMS_RETURN 退回 /
     * AIRSEND 航空发货 / AIRPICK 航空提货。
     *
     * <p><b>此前这里写的是 RETURN / REJECT / RETENTION —— 圆通没有这三个码</b>（照别家猜的）。
     * 真正的退回是 {@code TMS_RETURN}，于是「退回」一直掉进 default 被当成运输中：
     * 单子已经退回去了，买家看到的却是「运输中」，而且不报错。
     */
    static TraceStatus mapStatus(String ytoCode) {
        if (ytoCode == null) {
            return TraceStatus.UNKNOWN;
        }
        return switch (ytoCode.trim().toUpperCase()) {
            case "GOT" -> TraceStatus.PICKED;
            case "SENT_SCAN", "INBOUND" -> TraceStatus.DELIVERING;   // 派件 / 自提柜入柜
            case "SIGNED" -> TraceStatus.SIGNED;
            case "FAILED", "TMS_RETURN" -> TraceStatus.EXCEPTION;
            // ARRIVAL / DEPARTURE / FORWARDING / AIRSEND / AIRPICK
            default -> TraceStatus.IN_TRANSIT;
        };
    }

    /**
     * 解析圆通轨迹响应。<b>字段名与结构已按官方「物流轨迹查询接口」文档校准（2026-10-08）。</b>
     *
     * <p>查到时<b>顶层直接是数组</b>，按时间<b>正序</b>（最早的 GOT 在前、SIGNED 在最后）：
     * <pre>[{waybill_No, upload_Time:"yyyy-MM-dd HH:mm:ss", infoContent:"GOT",
     *   processInfo:"您的快件被【…】揽收", city:"金华市", district:"义乌市", weight:0.68}, …]</pre>
     *
     * <p>查不到时是<b>另一种结构</b>（对象，不是数组，且 success 是字符串）：
     * <pre>{"map":{"YT2600205450611":[]},"code":"1001","success":"true","message":"查询结果为空。"}</pre>
     * 两种都要认：只按其中一种写，另一种会静默解成空轨迹而不报错。
     *
     * <p><b>此前这里按 {@code result.traces[].{opCode,opTime,opName}} 写</b>，那是照文档「猜」的，
     * 与真实返回<b>无一字相符</b> —— 真跑起来每一单都是空轨迹、状态恒 UNKNOWN，而且不抛异常。
     */
    static TraceResult parse(String waybillNo, String carrier, JsonNode root) {
        List<TraceResult.TraceNode> nodes = new ArrayList<>();
        for (JsonNode t : tracesOf(root, waybillNo)) {
            String code = t.path("infoContent").asString("").trim().toUpperCase();
            nodes.add(new TraceResult.TraceNode(
                    parseTime(t.path("upload_Time").asString("")),
                    mapStatus(code),
                    t.path("processInfo").asString(""),
                    location(t), null, null, code.isEmpty() ? null : code));
        }
        // 端上要最新在前，而圆通给的是正序 —— 统一按时刻倒排（也顺带防它哪天不保证顺序）
        nodes.sort(Comparator.comparingLong(TraceResult.TraceNode::at).reversed());
        TraceStatus status = nodes.isEmpty() ? TraceStatus.UNKNOWN : nodes.getFirst().status();
        // 到柜只看最新那一个节点：取走之后会继续推进到签收
        boolean atLocker = !nodes.isEmpty() && "INBOUND".equals(nodes.getFirst().statusCode());
        return new TraceResult(waybillNo, carrier, status, "yto", nodes, null, atLocker);
    }

    /** 查到＝顶层数组；查不到＝{@code {"map":{"<运单号>":[]},…}}。两种结构都收，其余一律当空。 */
    private static Iterable<JsonNode> tracesOf(JsonNode root, String waybillNo) {
        if (root.isArray()) {
            return root;
        }
        JsonNode byNo = root.path("map").path(waybillNo);
        return byNo.isArray() ? byNo : List.of();
    }

    /** 节点位置：用 city（当前操作城市，与 TraceNode 的「城市/网点」同义）；取不到退 district。 */
    private static String location(JsonNode t) {
        String city = t.path("city").asString("");
        return city.isBlank() ? t.path("district").asString("") : city;
    }

    private static long parseTime(String s) {
        if (s == null || s.isBlank()) {
            return 0L;
        }
        try {
            return LocalDateTime.parse(s.trim(), TS).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;   // 解不动就当 0，不让一条时间格式把整单轨迹搞挂
        }
    }

}
