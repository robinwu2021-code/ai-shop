package ai.neargo.shop.channel.express.trace;

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
import java.net.URLEncoder;
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
            String param = json.writeValueAsString(java.util.Map.of("Number", waybillNo));
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

    private String post(String param) throws java.io.IOException, InterruptedException {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String form = "method=" + enc(method) + "&v=" + enc(version)
                + "&appKey=" + enc(appKey) + "&timestamp=" + enc(timestamp)
                + "&format=JSON&sign=" + enc(sign(param, method, version, secret))
                + "&param=" + enc(param);
        HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
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

    /** 圆通状态码 → 统一状态。GOT 揽收 / SIGNED 签收 / 退回滞留类异常 / 其余运输中 */
    static TraceStatus mapStatus(String ytoCode) {
        if (ytoCode == null) {
            return TraceStatus.UNKNOWN;
        }
        return switch (ytoCode.trim().toUpperCase()) {
            case "GOT" -> TraceStatus.PICKED;
            case "SIGNED" -> TraceStatus.SIGNED;
            case "FAILED", "RETURN", "REJECT", "RETENTION" -> TraceStatus.EXCEPTION;
            default -> TraceStatus.IN_TRANSIT;   // DEPARTURE / ARRIVAL / SENT_SCAN…
        };
    }

    /**
     * 解析圆通轨迹响应。
     *
     * <p>⚠️ <b>字段名按圆通开放文档写，上线前（Y3 拿到真账号）对一条真实响应校准</b> ——
     * 不同账号/版本的字段名可能不同；校准点集中在这一个方法，改这里即可，不动上面的链路。
     * 期望结构：{@code {result:{traces:[{opCode,opTime,opName,city}...]}}}，节点按时间倒序排好。
     */
    static TraceResult parse(String waybillNo, String carrier, JsonNode root) {
        JsonNode traces = root.path("result").path("traces");
        List<TraceResult.TraceNode> nodes = new ArrayList<>();
        if (traces.isArray()) {
            for (JsonNode t : traces) {
                String code = t.path("opCode").asString("");
                long at = parseTime(t.path("opTime").asString(""));
                nodes.add(new TraceResult.TraceNode(at, mapStatus(code),
                        t.path("opName").asString(""), t.path("city").asString("")));
            }
        }
        // 最新在前：圆通不保证顺序，统一按时刻倒序
        nodes.sort(Comparator.comparingLong(TraceResult.TraceNode::at).reversed());
        TraceStatus status = nodes.isEmpty() ? TraceStatus.UNKNOWN : nodes.getFirst().status();
        return new TraceResult(waybillNo, carrier, status, "yto", nodes);
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

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
