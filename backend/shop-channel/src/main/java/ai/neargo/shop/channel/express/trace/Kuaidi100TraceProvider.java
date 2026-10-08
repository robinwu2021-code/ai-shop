package ai.neargo.shop.channel.express.trace;

import ai.neargo.shop.spi.logistics.TraceProvider;
import ai.neargo.shop.spi.logistics.TraceResult;
import ai.neargo.shop.spi.logistics.TraceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快递100 实时查询的轨迹 provider（TDD-快递100轨迹查询）。聚合器：映射表里的承运商都认。
 *
 * <p><b>签名</b>：{@code MD5(param + key + customer)} 转 32 位大写 —— 与寄件的
 * {@code MD5(param + t + key + secret)} 不是一套，customer 是「授权信息」里那一项，不是 secret。
 *
 * <p><b>缺 key 或 customer 时 available()=false，不抛</b>：路由打日志后这一单空着（见 TraceProvider）。
 *
 * <p><b>频率</b>：快递100 要求同一单查询间隔 ≥ 30 分钟、24 小时 ≤ 48 次，否则锁单；
 * 调用方是 30 分钟一轮的轮询任务，满足要求，这里不另做缓存。
 */
@Component
public class Kuaidi100TraceProvider implements TraceProvider {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100TraceProvider.class);
    /**
     * 时间格式。文档写的是 {@code yyyy-MM-dd HH:mm:ss}，但<b>真实响应没实跑过</b> ——
     * 认不出就只能丢掉这个节点，而丢掉是静默的：轨迹变空、零报错。所以多认几种，且丢弃时出警告。
     */
    private static final List<DateTimeFormatter> TS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /**
     * 微信 delivery_id（{@code ExpressCompanies}）→ 快递100 公司码。2026-10-08 逐个对照快递100 官方编码表核过。
     *
     * <p>与寄件网关那份映射不同：寄件刻意不放顺丰（微信发货上报对顺丰要手机号，那边还没接），
     * 查询没有这个限制 —— 手机号由调用方传进来。
     */
    static final Map<String, String> CODES = codes();

    private static Map<String, String> codes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("SF", "shunfeng");
        m.put("ZTO", "zhongtong");
        m.put("YTO", "yuantong");
        m.put("YD", "yunda");
        m.put("STO", "shentong");
        m.put("JTSD", "jtexpress");
        m.put("JD", "jd");
        m.put("YZPY", "youzhengguonei");
        m.put("EMS", "ems");
        m.put("DBL", "debangkuaidi");
        m.put("HTKY", "huitongkuaidi");
        m.put("FWX", "fengwang");
        m.put("UC", "youshuwuliu");
        m.put("ZJS", "zhaijisong");
        return java.util.Collections.unmodifiableMap(m);
    }

    private final String key;
    private final String customer;
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public Kuaidi100TraceProvider(
            @Value("${shop.express.kuaidi100.key:}") String key,
            @Value("${shop.express.kuaidi100.customer:}") String customer,
            @Value("${shop.express.kuaidi100.host:https://poll.kuaidi100.com}") String host) {
        this.key = key == null ? "" : key.trim();
        this.customer = customer == null ? "" : customer.trim();
        this.endpoint = (host == null || host.isBlank() ? "https://poll.kuaidi100.com" : host.trim()) + "/poll/query.do";
    }

    @Override
    public String name() {
        return "kuaidi100";
    }

    @Override
    public boolean covers(String carrier) {
        return carrier != null && CODES.containsKey(carrier);
    }

    @Override
    public boolean available() {
        return !key.isEmpty() && !customer.isEmpty();
    }

    @Override
    public Optional<TraceResult> trace(String carrier, String waybillNo) {
        return trace(carrier, waybillNo, null);
    }

    /**
     * @param phone 收件人手机号。顺丰、顺丰快运、中通必填（快递100 要校验），其他承运商传了也无妨。
     *              <b>不进日志</b>
     */
    @Override
    public Optional<TraceResult> trace(String carrier, String waybillNo, String phone) {
        String com = carrier == null ? null : CODES.get(carrier);
        if (com == null || waybillNo == null || waybillNo.isBlank() || !available()) {
            return Optional.empty();
        }
        try {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("com", com);
            p.put("num", waybillNo.trim());
            if (phone != null && !phone.isBlank()) {
                p.put("phone", phone.trim());
            }
            // 4 才给 areaCenter（经纬度）与 routeInfo（出发/当前/目的城市）——地图与步骤条都靠它。
            // 1 只有行政区名与状态名，画不了地图。按单计费与 1 相同。
            p.put("resultv2", "4");
            p.put("show", "0");
            p.put("order", "desc");
            String param = json.writeValueAsString(p);
            String form = "customer=" + enc(customer) + "&sign=" + enc(sign(param, key, customer)) + "&param=" + enc(param);
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            String body = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
            return parse(json, carrier, waybillNo.trim(), body);
        } catch (Exception e) {
            log.warn("[trace:kuaidi100] {} {} 查询异常：{}", carrier, waybillNo, e.toString());
            return Optional.empty();
        }
    }

    static String sign(String param, String key, String customer) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest((param + key + customer).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) {
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 快递100 返回体 → 统一结构。
     *
     * <p>失败体是 {@code {"result":false,"returnCode":"500","message":"查询无结果…"}}：返回 empty（这一单本轮空着，
     * 不编造推进）。常见码：500 查无结果、408 手机号校验未过、503 签名错、504 太频繁、601 无可用单量。
     */
    static Optional<TraceResult> parse(ObjectMapper json, String carrier, String waybillNo, String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode n = json.readTree(body);
            if (n.has("result") && !n.path("result").asBoolean(true)) {
                log.info("[trace:kuaidi100] {} {} 没有结果：{} {}", carrier, waybillNo,
                        n.path("returnCode").asText(""), n.path("message").asText(""));
                return Optional.empty();
            }
            if (!"ok".equalsIgnoreCase(n.path("message").asText(""))) {
                log.info("[trace:kuaidi100] {} {} 返回异常：{}", carrier, waybillNo, n.path("message").asText(""));
                return Optional.empty();
            }
            TraceStatus overall = statusOf(n.path("state").asText(""));
            List<TraceResult.TraceNode> nodes = new ArrayList<>();
            for (JsonNode d : n.path("data")) {
                String text = d.path("context").asText("").trim();
                if (text.isEmpty()) {
                    continue;
                }
                Long at = parseAt(d.path("ftime").asText(""), d.path("time").asText(""));
                if (at == null) {
                    // 认不出时间就只能丢，但**要出声**：静默丢掉等于轨迹凭空变空、零报错
                    log.warn("[trace:kuaidi100] {} {} 节点时间认不出，已丢弃：ftime={} time={}",
                            carrier, waybillNo, d.path("ftime").asText(""), d.path("time").asText(""));
                    continue;
                }
                String loc = d.path("areaName").asText(d.path("location").asText("")).trim();
                int[] ll = parseCenter(d.path("areaCenter").asText(""));
                String sc = d.path("statusCode").asText("").trim();
                nodes.add(new TraceResult.TraceNode(at, nodeStatus(d.path("status").asText("")), text,
                        loc.isEmpty() ? null : loc,
                        ll == null ? null : ll[0], ll == null ? null : ll[1],
                        sc.isEmpty() ? null : sc));
            }
            // 统一约定按时间倒序（最新在前）—— 不依赖对方的 order 参数
            nodes.sort((a, b) -> Long.compare(b.at(), a.at()));
            return Optional.of(new TraceResult(waybillNo, carrier, overall, "kuaidi100",
                    List.copyOf(nodes), parseRoute(n.path("routeInfo"))));
        } catch (Exception e) {
            log.warn("[trace:kuaidi100] {} {} 返回体解析失败", carrier, waybillNo);
            return Optional.empty();
        }
    }

    /**
     * {@code areaCenter} 是 {@code "经度,纬度"}（如 {@code "116.277912,32.353038"}）→ {@code [latE6, lngE6]}。
     *
     * <p><b>顺序容易写反</b>：快递100 给的是经度在前，而我们库里与端上一律 lat 在前。
     * 写反的症状是地图上所有点跑到国外，但<b>不报错</b>。所以这里按范围兜一道：
     * 中国境内纬度 3~54、经度 73~136，取到的两个数若不在各自范围内就丢弃这一组坐标。
     */
    static int[] parseCenter(String areaCenter) {
        if (areaCenter == null || areaCenter.isBlank()) {
            return null;
        }
        String[] parts = areaCenter.trim().split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            double lng = Double.parseDouble(parts[0].trim());
            double lat = Double.parseDouble(parts[1].trim());
            if (lat < 3 || lat > 54 || lng < 73 || lng > 136) {
                return null;
            }
            return new int[]{(int) Math.round(lat * 1e6), (int) Math.round(lng * 1e6)};
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code routeInfo: {from:{name}, cur:{name}, to:{name}}} → 城市路线；一个都没有时返回 null */
    static TraceResult.Route parseRoute(JsonNode routeInfo) {
        if (routeInfo == null || routeInfo.isMissingNode() || !routeInfo.isObject()) {
            return null;
        }
        String from = routeInfo.path("from").path("name").asText("").trim();
        String cur = routeInfo.path("cur").path("name").asText("").trim();
        String to = routeInfo.path("to").path("name").asText("").trim();
        if (from.isEmpty() && cur.isEmpty() && to.isEmpty()) {
            return null;
        }
        return new TraceResult.Route(from.isEmpty() ? null : from,
                cur.isEmpty() ? null : cur, to.isEmpty() ? null : to);
    }

    /** 两个时间字段、几种格式都试一遍；都认不出返回 null（调用方出警告后丢弃该节点） */
    static Long parseAt(String ftime, String time) {
        for (String raw : new String[]{ftime, time}) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String v = raw.trim();
            for (DateTimeFormatter f : TS_FORMATS) {
                try {
                    return LocalDateTime.parse(v, f).atZone(CN).toInstant().toEpochMilli();
                } catch (Exception ignored) {
                    // 下一种格式
                }
            }
        }
        return null;
    }

    /**
     * 快递100 主状态码 → 统一状态（TDD §3）。三位的高级状态码（101/301…）取百位当主状态。
     */
    static TraceStatus statusOf(String state) {
        int s;
        try {
            s = Integer.parseInt(state.trim());
        } catch (Exception e) {
            return TraceStatus.UNKNOWN;
        }
        if (s >= 100) {
            s = s / 100;
        }
        return switch (s) {
            case 1 -> TraceStatus.PICKED;
            case 0, 5, 7, 8, 10, 11, 12 -> TraceStatus.IN_TRANSIT;
            case 3 -> TraceStatus.SIGNED;
            case 2, 4, 6, 13, 14 -> TraceStatus.EXCEPTION;
            default -> TraceStatus.UNKNOWN;
        };
    }

    /** 节点上的状态名（resultv2=1 时返回「揽收 / 在途 / 派件 / 签收 / 疑难…」）→ 统一状态 */
    static TraceStatus nodeStatus(String name) {
        if (name == null || name.isBlank()) {
            return TraceStatus.IN_TRANSIT;
        }
        if (name.contains("签收") && !name.contains("拒")) {
            return TraceStatus.SIGNED;
        }
        if (name.contains("揽收")) {
            return TraceStatus.PICKED;
        }
        if (name.contains("疑难") || name.contains("退") || name.contains("拒") || name.contains("异常")) {
            return TraceStatus.EXCEPTION;
        }
        return TraceStatus.IN_TRANSIT;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
