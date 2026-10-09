package ai.neargo.shop.notify.port;

import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.WxSubscribePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信小程序订阅消息（{@code subscribeMessage.send}）。2026-08 核对自官方文档。
 *
 * <p><b>不引官方 SDK</b>：本项目一律 {@code mvn -o} 离线构建（同 {@link AliSmsGateway}）。
 * 订阅消息只有两个接口（取 token、发消息），请求与响应都是平铺 JSON，JDK 自带的够用。
 *
 * <p><b>access_token 用 {@code stable_token}</b> 而不是老的 {@code cgi-bin/token}：
 * 老接口每次调用都签发新 token 并挤掉旧的 —— 多实例部署时两个实例会互相踢对方的 token，
 * 表现为「随机的 40001」，极难排查。stable_token 在有效期内返回同一个，天然多实例安全。
 *
 * <p><b>模板字段名写死在本类</b>（{@code thing/number/amount…}）：它们是 mp 后台报备
 * 模板时定下的通道概念，与阿里云短信的模板参数同理 —— 换模板改这里，不该惊动领域代码。
 *
 * <p><b>启动即校验凭据</b>：缺 appid/secret/模板号时直接起不来，不静默退回桩（同短信通道，
 * 静默退回的表现是「已发送」日志照常出现而用户一条都收不到）。
 */
@Component("wxSubscribeGateway")
@ConditionalOnProperty(name = "shop.wx.subscribe.stub", havingValue = "false")
public class WxSubscribeGateway implements WxSubscribePort {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeGateway.class);

    private static final String STR_FIELD = "\"%s\"\\s*:\\s*\"([^\"]*)\"";
    private static final String NUM_FIELD = "\"%s\"\\s*:\\s*(-?\\d+)";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private final String host;
    private final String appid;
    private final String secret;
    private final String tplOrderArrived;
    private final String tplRefunded;
    private final String tplNewGoods;
    /** 元器件询价结果通知（可选）。模板是用户在 mp 后台自选的，字段名见 {@link #elecQuotedFields} */
    private final String tplElecQuoted;
    /**
     * 元器件模板的四个字段名，按「单号, 料号概述, 结果, 提示语」的顺序。
     *
     * <p><b>与另外三个场景不同，这里不写死</b>：那三个模板是已经报备过的，字段名定了；
     * 这一个要等用户在 mp 后台选定模板才知道长什么样（公共模板库里「报价结果」一类的字段各不相同）。
     * 配成环境变量，选定模板那天改一个变量，不用发版。字段类型看前缀截断（thing 20 字、phrase 5 字…）
     */
    private final String[] elecQuotedFields;
    /** {@code developer} / {@code trial} / {@code formal}。联调时切 trial 免得打扰真实用户。 */
    private final String mpState;

    /**
     * 快递三个节点的模板（可选，TDD-物流模块 批 4）：场景 → 模板号与字段名。
     *
     * <p>字段名与元器件那条同一个理由不写死：要等 mp 后台选定模板才知道长什么样。
     * 按「订单号, 快递公司, 运单号, 状态, 时间, 提示语」六个位置配，模板里没有的位置留空。
     * <b>不走构造器</b>：可选配置，测试直接 new 的那两个构造器不必跟着改。
     */
    private final Map<String, WaybillTpl> waybillTpls = new java.util.HashMap<>();

    private record WaybillTpl(String templateId, String[] fields) {
    }

    /** stable_token 缓存。到期前 5 分钟就换新，避免拿着一个正好过期的 token 去发。 */
    private volatile String token;
    private volatile long tokenExpireAt;

    /** 不带元器件场景的构造（元器件那个模板是可选的，缺了只是那一个场景关着）。测试直接 new 它 */
    public WxSubscribeGateway(String host, String appid, String secret, String tplOrderArrived, String tplRefunded,
                              String tplNewGoods, String mpState, boolean loginStub) {
        this(host, appid, secret, tplOrderArrived, tplRefunded, tplNewGoods, "", "", mpState, loginStub);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WxSubscribeGateway(@Value("${shop.wx.host:https://api.weixin.qq.com}") String host,
                              @Value("${shop.wx.appid:}") String appid,
                              @Value("${shop.wx.secret:}") String secret,
                              @Value("${shop.wx.templates.order-arrived:}") String tplOrderArrived,
                              @Value("${shop.wx.templates.refunded:}") String tplRefunded,
                              @Value("${shop.wx.templates.new-goods:}") String tplNewGoods,
                              @Value("${shop.wx.templates.elec-quoted:}") String tplElecQuoted,
                              @Value("${shop.wx.templates.elec-quoted-fields:character_string1,thing2,phrase3,thing4}")
                              String elecQuotedFields,
                              @Value("${shop.wx.mp-state:formal}") String mpState,
                              @Value("${shop.wx.login.stub:true}") boolean loginStub) {
        this.host = host;
        this.appid = appid;
        this.secret = secret;
        this.tplOrderArrived = tplOrderArrived;
        this.tplRefunded = tplRefunded;
        this.tplNewGoods = tplNewGoods;
        this.tplElecQuoted = tplElecQuoted;
        this.elecQuotedFields = elecQuotedFields == null ? new String[0]
                : java.util.Arrays.stream(elecQuotedFields.split(",", -1)).map(String::trim).toArray(String[]::new);
        this.mpState = mpState;
        /*
         * 两条通道的开关拆开之后，出现了一个此前不可能存在的组合：登录走桩、订阅消息真发。
         * 那意味着库里存的是**假 openid**（桩把 wx.login 的 code 直接当 openid），
         * 而这里拿着它去调 subscribeMessage.send —— 每一条都会以 40003 失败，
         * 且失败发生在异步发送里，日志上看是「发过了」。
         *
         * 拆开关的收益只在「登录先真、订阅消息后真」这一个方向上，反方向没有任何用途，
         * 所以不留给人去记，直接在启动时拒绝。
         */
        if (loginStub) {
            throw new IllegalStateException(
                    "订阅消息已开启（shop.wx.subscribe.stub=false）但登录还是桩（shop.wx.login.stub=true）"
                            + " —— 库里是假 openid，发出去每条都是 40003。要真发就先把登录也切真");
        }
        require(appid, "WX_APPID");
        require(secret, "WX_SECRET");
        /*
         * **到货通知是这条通道存在的理由，没有它就别开。**
         * 自提模式下「货到了没人知道」直接变成自提点压货。
         */
        require(tplOrderArrived, "WX_TPL_ORDER_ARRIVED（mp 后台报备的到货通知模板号）");
        /*
         * **退款通知是可选的，缺了不拦启动。**
         *
         * 原先这里和到货一样 require ——那假设了「两个模板都会有」，
         * 而 2026-09 在新小程序申请时发现：公共模板库里能选到「购物服务动态」
         * （到货）与「新品开售提醒」，退款那类并不是每个类目都有。
         * 更要紧的是**微信支付本身会给用户发退款到账通知**，我们再发一条是重复的。
         *
         * 缺了不是静默跳过：启动时打一条 WARN 说清楚哪个场景是关的，
         * 真去发的时候（{@link #sendRefunded}）直接抛，
         * 而不是「发了、没到、没人知道」—— 后者才是这条 require 当初要防的东西。
         */
        if (tplRefunded == null || tplRefunded.isBlank()) {
            log.warn("[wxsub] 未配 WX_TPL_REFUNDED —— 退款通知场景**关闭**。"
                    + "微信支付自带退款到账通知，多数情况下不需要我们再发一条；"
                    + "确实要发就去 mp 后台申请模板并配上这个变量");
        }
        /*
         * **新品开售提醒也是可选的**，口径与退款那条一致：缺了不拦启动，
         * 真去发的时候（{@link #sendNewGoods}）才抛。
         *
         * 它和退款不同的地方在于「缺了的后果」：退款缺了无所谓（微信支付自带到账通知），
         * 而这条缺了意味着**用户点过的那次订阅授权白点了** —— 额度扣得下去、消息发不出来。
         * 所以 WARN 里要说清是哪一头没配。
         */
        if (tplNewGoods == null || tplNewGoods.isBlank()) {
            log.warn("[wxsub] 未配 WX_TPL_NEW_GOODS —— 新品开售提醒场景**关闭**。"
                    + "端上若已经在收集这个模板的授权，用户点的「允许」会白点："
                    + "额度记下了，而发的时候没有模板号可用");
        }
        log.info("[wxsub] 订阅消息通道已启用 appid={} state={}", appid, mpState);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void waybillTemplates(@Value("${shop.wx.templates.waybill-picked-up:}") String pickedUp,
                          @Value("${shop.wx.templates.waybill-picked-up-fields:}") String pickedUpFields,
                          @Value("${shop.wx.templates.waybill-delivering:}") String delivering,
                          @Value("${shop.wx.templates.waybill-delivering-fields:}") String deliveringFields,
                          @Value("${shop.wx.templates.waybill-signed:}") String signed,
                          @Value("${shop.wx.templates.waybill-signed-fields:}") String signedFields) {
        putWaybill(SCENE_WAYBILL_PICKED_UP, pickedUp, pickedUpFields);
        putWaybill(SCENE_WAYBILL_DELIVERING, delivering, deliveringFields);
        putWaybill(SCENE_WAYBILL_SIGNED, signed, signedFields);
    }

    private void putWaybill(String scene, String templateId, String fields) {
        if (templateId == null || templateId.isBlank()) {
            return;   // 没选模板：这个节点不发订阅消息，站内信照发
        }
        waybillTpls.put(scene, new WaybillTpl(templateId.trim(), fields == null ? new String[0]
                : java.util.Arrays.stream(fields.split(",", -1)).map(String::trim).toArray(String[]::new)));
    }

    private static void require(String v, String envName) {
        if (v == null || v.isBlank()) {
            throw new IllegalStateException(
                    "订阅消息通道已开启（shop.wx.subscribe.stub=false）但缺少配置：" + envName
                            + " —— 不配就发不出去，这里直接失败而不是退回桩");
        }
    }

    /**
     * 场景 → 环境变量里配的模板号。
     *
     * <p><b>运营的覆盖不在这里读</b>：那是领域配置，由 message 域的装饰器
     * （{@code NotifyLoggingWxSubscribePort}）叠在上面 —— 通道只认自己那份配置，
     * 不碰数据库。这样「运营改了模板号」在走桩时同样生效，
     * 而不是只有接了真通道才看得出来。
     */
    @Override
    public String templateId(String scene) {
        return switch (scene) {
            case SCENE_ORDER_ARRIVED -> tplOrderArrived;
            case SCENE_REFUNDED -> tplRefunded;
            case SCENE_NEW_GOODS -> tplNewGoods;
            case SCENE_ELEC_QUOTED -> tplElecQuoted;
            case SCENE_WAYBILL_PICKED_UP, SCENE_WAYBILL_DELIVERING, SCENE_WAYBILL_SIGNED -> {
                WaybillTpl t = waybillTpls.get(scene);
                yield t == null ? null : t.templateId();
            }
            default -> null;
        };
    }

    @Override
    public SendResult sendOrderArrived(String openId, int orderCount, String page, String tip) {
        // 字段名与报备模板一致：number1=到货件数 thing2=提示语
        Map<String, String> data = new LinkedHashMap<>();
        data.put("number1", String.valueOf(orderCount));
        // thing 类字段微信限 20 字，超了整条会被拒 —— 在这里截断，
        // 让「填长了」表现为少几个字，而不是整条发不出去
        data.put("thing2", clamp(tip == null || tip.isBlank()
                ? "包裹已到自提点，请凭取货码取货" : tip, 20));
        return send(openId, tplOrderArrived, page, data);
    }

    @Override
    public SendResult sendRefunded(String openId, String amountText, String page, String tip) {
        // 没配模板号还来发 —— 明确抛，不可重试。静默跳过会变成「发了、没到、没人知道」
        if (tplRefunded == null || tplRefunded.isBlank()) {
            throw new WxSubscribeException(
                    "退款通知未接入（WX_TPL_REFUNDED 未配）—— 该场景在本小程序上没有报备模板", false);
        }
        // amount1=退款金额 thing2=提示语。截断口径与到货那条一致（见 sendOrderArrived）
        Map<String, String> data = new LinkedHashMap<>();
        data.put("amount1", amountText);
        data.put("thing2", clamp(tip == null || tip.isBlank()
                ? "退款将原路退回，到账以支付渠道为准" : tip, 20));
        return send(openId, tplRefunded, page, data);
    }

    @Override
    public SendResult sendNewGoods(String openId, String goodsTitle, String goodsDesc,
                                   long onSaleAt, String page, String tip) {
        if (tplNewGoods == null || tplNewGoods.isBlank()) {
            throw new WxSubscribeException(
                    "新品开售提醒未接入（WX_TPL_NEW_GOODS 未配）—— 端上收集的授权无处可用", false);
        }
        /*
         * 字段名来自 mp 后台报备的模板 383：
         *   thing4=新品名称  thing5=新品详情  date6=开售时间  thing7=温馨提示
         *
         * **date 类字段的格式是微信定的**（`yyyy年M月d日 HH:mm` 这一类），格式化留在通道里 ——
         * 领域侧只给时间戳。给错格式整条被拒，而那是通道概念不是业务概念。
         */
        Map<String, String> data = new LinkedHashMap<>();
        data.put("thing4", clamp(goodsTitle, 20));
        data.put("thing5", clamp(goodsDesc == null || goodsDesc.isBlank() ? goodsTitle : goodsDesc, 20));
        data.put("date6", DATE_FMT.format(
                java.time.Instant.ofEpochMilli(onSaleAt).atZone(java.time.ZoneId.systemDefault())));
        data.put("thing7", clamp(tip == null || tip.isBlank()
                ? "想继续收到，回店铺再点一次收藏" : tip, 20));
        return send(openId, tplNewGoods, page, data);
    }

    @Override
    public SendResult sendElecQuoted(String openId, String rfqNo, String summary, String resultText, String page,
                                     String tip) {
        if (tplElecQuoted == null || tplElecQuoted.isBlank()) {
            throw new WxSubscribeException(
                    "元器件询价结果通知未接入（WX_TPL_ELEC_QUOTED 未配）—— 站内信照发", false);
        }
        if (elecQuotedFields.length < 4) {
            throw new WxSubscribeException(
                    "WX_TPL_ELEC_QUOTED_FIELDS 要按「单号,料号概述,结果,提示语」配四个字段名", false);
        }
        String[] values = {rfqNo, summary, resultText,
                tip == null || tip.isBlank() ? "点开查看报价，报价有有效期" : tip};
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < 4; i++) {
            if (!elecQuotedFields[i].isEmpty()) {
                data.put(elecQuotedFields[i], clampByType(elecQuotedFields[i], values[i]));
            }
        }
        return send(openId, tplElecQuoted, page, data);
    }

    @Override
    public SendResult sendWaybill(String openId, String scene, WaybillNotice n, String page) {
        WaybillTpl t = waybillTpls.get(scene);
        if (t == null) {
            throw new WxSubscribeException("快递节点通知未接入（" + scene + " 没配模板号）—— 站内信照发", false);
        }
        if (t.fields().length < 6) {
            throw new WxSubscribeException("快递节点模板的字段名要按「订单号,快递公司,运单号,状态,时间,提示语」"
                    + "配六个位置（模板里没有的留空）：" + scene, false);
        }
        String[] values = {n.orderNo(), n.carrierName(), n.waybillNo(), n.statusText(),
                DATE_FMT.format(java.time.Instant.ofEpochMilli(n.at()).atZone(java.time.ZoneId.systemDefault())),
                n.tip() == null || n.tip().isBlank() ? "点开订单查看物流详情" : n.tip()};
        Map<String, String> data = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            if (!t.fields()[i].isEmpty()) {
                data.put(t.fields()[i], clampByType(t.fields()[i], values[i]));
            }
        }
        return send(openId, t.templateId(), page, data);
    }

    /** 按微信字段类型截断：thing 20 字、phrase 5 字、character_string 32 位、name 10 字；其余原样 */
    private static String clampByType(String field, String v) {
        String s = v == null ? "" : v;
        if (field.startsWith("thing")) {
            return clamp(s, 20);
        }
        if (field.startsWith("phrase")) {
            return clamp(s, 5);
        }
        if (field.startsWith("character_string")) {
            return clamp(s, 32);
        }
        if (field.startsWith("name")) {
            return clamp(s, 10);
        }
        return s;
    }

    /** 微信 {@code date} 类字段的格式。**不要改成 ISO** —— 那种格式微信不认，整条被拒。 */
    private static final java.time.format.DateTimeFormatter DATE_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm");

    private SendResult send(String openId, String templateId, String page, Map<String, String> data) {
        StringBuilder body = new StringBuilder()
                .append("{\"touser\":\"").append(esc(openId))
                .append("\",\"template_id\":\"").append(esc(templateId))
                .append("\",\"miniprogram_state\":\"").append(esc(mpState)).append('"');
        if (page != null && !page.isBlank()) {
            body.append(",\"page\":\"").append(esc(page)).append('"');
        }
        body.append(",\"data\":{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) {
                body.append(',');
            }
            first = false;
            body.append('"').append(esc(e.getKey())).append("\":{\"value\":\"")
                    .append(esc(e.getValue())).append("\"}");
        }
        body.append("}}");

        String resp = post("/cgi-bin/message/subscribe/send?access_token=" + accessToken(false),
                body.toString());
        int code = intField(resp, "errcode");
        if (code == 40001 || code == 42001) {
            // token 失效（后台改过 secret、或本地缓存跨过了有效期）：强刷一次再试，只试一次
            resp = post("/cgi-bin/message/subscribe/send?access_token=" + accessToken(true),
                    body.toString());
            code = intField(resp, "errcode");
        }
        if (code != 0) {
            /*
             * 43101 = 用户未订阅或额度已用完。领域侧扣过额度才会走到这里，出现即说明
             * 两边的账对不上（比如用户在微信设置里关了通知）—— 记下来但不重试。
             */
            /*
             * 47003 = 模板参数不对。**几乎总是字段名的数字后缀错了。**
             *
             * 公共模板库里字段名由关键词的**选择顺序**决定：先选「商品数量」
             * 后选「商品详情」得到 number1 + thing2，反过来就是 thing1 + number2。
             * 而这件事**在真正有人订阅之前验不出来** —— 微信是先查订阅再校验字段，
             * 没有配额时错的字段名和对的字段名都回 43101（2026-09-03 实测，
             * 带对照组：故意写错的一组返回了同样的码）。
             *
             * 所以把话说在这里：第一条真实发送失败时，让日志直接指向该去看哪儿。
             */
            String hint = code == 47003
                    ? " —— 模板字段名对不上。去 mp 后台该模板的「详情」看 {{...}} 里的确切名字"
                      + "（顺序决定数字后缀），改 WxSubscribeGateway 里 data.put 的键"
                    : "";
            throw new WxSubscribeException(
                    "微信拒绝：" + code + " " + strField(resp, "errmsg") + hint, false);
        }
        return SendResult.of(strField(resp, "msgid"), templateId);
    }

    /**
     * @param forceRefresh 收到 40001 时置 true。stable_token 本身有 force_refresh 参数，
     *                     但常规刷新**不要传** —— 传了就退化成老 token 接口的互踢行为
     */
    private String accessToken(boolean forceRefresh) {
        if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
            return token;
        }
        synchronized (this) {
            if (!forceRefresh && token != null && System.currentTimeMillis() < tokenExpireAt) {
                return token;
            }
            String resp = post("/cgi-bin/stable_token",
                    "{\"grant_type\":\"client_credential\",\"appid\":\"" + esc(appid)
                            + "\",\"secret\":\"" + esc(secret) + "\""
                            + (forceRefresh ? ",\"force_refresh\":true" : "") + "}");
            String t = strField(resp, "access_token");
            if (t == null || t.isBlank()) {
                throw new WxSubscribeException(
                        "取 access_token 失败：" + intField(resp, "errcode") + " "
                                + strField(resp, "errmsg"), false);
            }
            long expiresIn = Math.max(60, intField(resp, "expires_in"));
            token = t;
            tokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
            return t;
        }
    }

    private String post(String path, String jsonBody) {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(host + path))
                            .header("Content-Type", "application/json")
                            .timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.body();
        } catch (java.io.IOException e) {
            throw new WxSubscribeException("微信接口网络失败：" + e.getMessage(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WxSubscribeException("微信接口调用被中断", true);
        }
    }

    /** 微信 {@code thing} 字段限 20 字。超长整条被拒，所以宁可少几个字。 */
    private static String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 只处理 JSON 字符串里必须转义的两个字符；换行等控制字符不会出现在这些字段里。 */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String strField(String json, String name) {
        Matcher m = Pattern.compile(STR_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? m.group(1) : null;
    }

    /** 字段缺失返回 0 —— 微信成功响应里往往不带 errcode，缺失即成功。 */
    private static int intField(String json, String name) {
        Matcher m = Pattern.compile(NUM_FIELD.formatted(name)).matcher(json == null ? "" : json);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }
}
