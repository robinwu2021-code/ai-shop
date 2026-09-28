package ai.neargo.shop.channel.express.port;

import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 快递100 商家寄件（上门取件 · 线上支付）。文档：api.kuaidi100.com「上门取件API（线上支付）」。
 *
 * <p>三条接口同一个地址 {@code /order/borderapi.do}，用 {@code method} 区分：
 * {@code bOrder} 下单、{@code cancel} 取消、{@code price} 查价。
 * 请求签名 {@code MD5(param + t + key + secret)} 转大写；回调签名 {@code MD5(param + salt)} 转大写。
 *
 * <p><b>运费由平台账户预充值实扣</b>（ADR-028）。余额不足时快递100 回 601，按拒单处理并把原话给商家。
 */
@Component
@ConditionalOnProperty(name = "shop.express.kuaidi100.stub", havingValue = "false")
public class Kuaidi100PickupGateway implements ExpressPickupPort {

    private static final Logger log = LoggerFactory.getLogger(Kuaidi100PickupGateway.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 微信 delivery_id → 快递100 公司码。<b>映射只此一处</b>，顺序就是商家看到的顺序。
     *
     * <p>不放顺丰：微信发货上报对顺丰要求收件人手机号（receiver_contact），我们的上报还没接这一项 ——
     * 放进来的话，这一单取件、扣了运费，却报不上发货，货款一直冻着（TDD §0「本期不做」）。
     */
    static final Map<String, String> CODES = orderedCodes();

    private static Map<String, String> orderedCodes() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ZTO", "zhongtong");
        m.put("YTO", "yuantong");
        m.put("YD", "yunda");
        m.put("STO", "shentong");
        m.put("JTSD", "jtexpress");
        m.put("JD", "jd");
        m.put("DBL", "debangkuaidi");
        m.put("EMS", "ems");
        return java.util.Collections.unmodifiableMap(m);
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String endpoint;
    /** 测试环境：下单不产生真实取件、不扣费；状态在「寄件测试平台」手动推（TDD §7 AC9） */
    private final String sandboxEndpoint;
    private final String key;
    private final String secret;
    private final String salt;
    private final String callbackUrl;

    public Kuaidi100PickupGateway(
            @Value("${shop.express.kuaidi100.host:https://poll.kuaidi100.com}") String host,
            @Value("${shop.express.kuaidi100.sandbox-host:http://e-test.kuaidilab.com/api}") String sandboxHost,
            @Value("${shop.express.kuaidi100.key:}") String key,
            @Value("${shop.express.kuaidi100.secret:}") String secret,
            @Value("${shop.express.kuaidi100.salt:}") String salt,
            @Value("${shop.express.kuaidi100.callback-url:}") String callbackUrl) {
        if (blank(key) || blank(secret) || blank(salt) || blank(callbackUrl)) {
            // 开了却缺凭据：启动就失败，别让它上线后在第一个商家点「叫快递」时才报
            throw new IllegalStateException("快递100 代下单已开启（shop.express.kuaidi100.stub=false）"
                    + "但缺少 KUAIDI100_KEY / KUAIDI100_SECRET / KUAIDI100_SALT / 回调地址");
        }
        this.endpoint = host + "/order/borderapi.do";
        this.sandboxEndpoint = sandboxHost + "/order/borderapi.do";
        this.key = key;
        this.secret = secret;
        this.salt = salt;
        this.callbackUrl = callbackUrl;
        log.info("[kd100] 快递代下单通道已启用 callback={}", callbackUrl);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public List<String> carriers() {
        return List.copyOf(CODES.keySet());
    }

    @Override
    public Optional<Quote> quote(String carrier, String senderAddress, String receiverAddress, int weightG,
                                 boolean sandbox) {
        String com = CODES.get(carrier);
        if (com == null) {
            return Optional.empty();
        }
        ObjectNode p = JSON.createObjectNode();
        p.put("kuaidicom", com);
        p.put("sendManPrintAddr", senderAddress);
        p.put("recManPrintAddr", receiverAddress);
        p.put("weight", kg(weightG));
        try {
            JsonNode resp = post("price", p.toString(), Duration.ofSeconds(4), sandbox);
            if (!ok(resp)) {
                log.info("[kd100] 查价无结果 com={} code={} msg={}", com, resp.path("returnCode").asText(),
                        resp.path("message").asText());
                return Optional.empty();
            }
            JsonNode d = resp.path("data");
            if (d.isArray()) {
                d = d.path(0);
            }
            Long price = minor(d.path("price").asText(null));
            Long list = minor(d.path("defPrice").asText(null));
            if (price == null) {
                return Optional.empty();
            }
            return Optional.of(new Quote(carrier, price, list == null ? price : list));
        } catch (Exception e) {
            log.warn("[kd100] 查价失败 com={}：{}", com, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public Booked create(CreateCmd cmd) {
        String com = CODES.get(cmd.carrier());
        if (com == null) {
            return Booked.fail("unsupported carrier " + cmd.carrier());
        }
        ObjectNode p = JSON.createObjectNode();
        p.put("kuaidicom", com);
        p.put("recManName", cmd.receiver().name());
        p.put("recManMobile", cmd.receiver().mobile());
        p.put("recManPrintAddr", cmd.receiver().address());
        p.put("sendManName", cmd.sender().name());
        p.put("sendManMobile", cmd.sender().mobile());
        p.put("sendManPrintAddr", cmd.sender().address());
        p.put("callBackUrl", callbackUrl);
        p.put("cargo", cmd.cargo());
        p.put("weight", kg(cmd.weightG()));
        p.put("salt", salt);
        p.put("thirdOrderId", cmd.thirdOrderNo());
        try {
            JsonNode resp = post("bOrder", p.toString(), Duration.ofSeconds(10), cmd.sandbox());
            if (!ok(resp)) {
                String msg = resp.path("message").asText("下单失败");
                log.warn("[kd100] 下单被拒 no={} code={} msg={}", cmd.thirdOrderNo(),
                        resp.path("returnCode").asText(), msg);
                return Booked.fail(msg);
            }
            JsonNode d = resp.path("data");
            log.info("[kd100] 下单成功 no={} taskId={}{}", cmd.thirdOrderNo(), d.path("taskId").asText(),
                    cmd.sandbox() ? "（测试环境）" : "");
            return new Booked(true, text(d, "taskId"), text(d, "orderId"), text(d, "kuaidinum"),
                    resp.path("message").asText(null));
        } catch (Exception e) {
            log.warn("[kd100] 下单异常 no={}：{}", cmd.thirdOrderNo(), e.toString());
            return Booked.fail("快递100 暂时连不上，请稍后再试");
        }
    }

    @Override
    public Booked cancel(String taskId, String providerOrderId, String reason, boolean sandbox) {
        ObjectNode p = JSON.createObjectNode();
        p.put("taskId", taskId);
        p.put("orderId", providerOrderId);
        p.put("cancelMsg", reason == null ? "商家取消" : reason.length() > 30 ? reason.substring(0, 30) : reason);
        try {
            JsonNode resp = post("cancel", p.toString(), Duration.ofSeconds(10), sandbox);
            if (!ok(resp)) {
                return Booked.fail(resp.path("message").asText("取消失败"));
            }
            return new Booked(true, taskId, providerOrderId, null, resp.path("message").asText(null));
        } catch (Exception e) {
            log.warn("[kd100] 取消异常 taskId={}：{}", taskId, e.toString());
            return Booked.fail("快递100 暂时连不上，请稍后再试");
        }
    }

    @Override
    public Optional<Callback> parseCallback(String taskId, String sign, String param) {
        return parse(taskId, sign, param, salt);
    }

    /** 验签 + 解析，静态以便单测直接验报文，不必起 Spring */
    static Optional<Callback> parse(String taskId, String sign, String param, String salt) {
        if (param == null || sign == null || !md5Upper(param + salt).equalsIgnoreCase(sign.trim())) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(param);
            JsonNode d = root.path("data");
            int status = d.hasNonNull("status") ? d.path("status").asInt(-1) : root.path("status").asInt(-1);
            String tracking = firstText(d, root, "kuaidinum");
            return Optional.of(new Callback(taskId, text(d, "orderId"), status, tracking,
                    grams(d.path("weight").asText(null)), minor(d.path("freight").asText(null)),
                    minor(d.path("defPrice").asText(null)), text(d, "courierName"), text(d, "courierMobile"),
                    firstText(d, root, "message")));
        } catch (Exception e) {
            log.warn("[kd100] 回调报文解析失败 taskId={}：{}", taskId, e.toString());
            return Optional.empty();
        }
    }

    // ── 报文 ─────────────────────────────────────────────────────────────

    private JsonNode post(String method, String param, Duration timeout, boolean sandbox) throws Exception {
        String t = String.valueOf(System.currentTimeMillis());
        String form = "method=" + enc(method) + "&key=" + enc(key) + "&t=" + t
                + "&sign=" + enc(requestSign(param, t, key, secret)) + "&param=" + enc(param);
        HttpRequest req = HttpRequest.newBuilder(URI.create(sandbox ? sandboxEndpoint : endpoint))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        String body = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
        return JSON.readTree(body);
    }

    static String requestSign(String param, String t, String key, String secret) {
        return md5Upper(param + t + key + secret);
    }

    private static boolean ok(JsonNode resp) {
        return resp.path("result").asBoolean(false) && "200".equals(resp.path("returnCode").asText());
    }

    static String md5Upper(String s) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 元（字符串）→ 分。空或不是数字时为空，不当成 0 —— 0 元运费与「没给运费」是两回事 */
    static Long minor(String yuan) {
        if (yuan == null || yuan.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(yuan.trim()).movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Integer grams(String kg) {
        if (kg == null || kg.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(kg.trim()).movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String kg(int grams) {
        return BigDecimal.valueOf(grams).movePointLeft(3).stripTrailingZeros().toPlainString();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private static String firstText(JsonNode a, JsonNode b, String field) {
        String v = text(a, field);
        return v != null ? v : text(b, field);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
