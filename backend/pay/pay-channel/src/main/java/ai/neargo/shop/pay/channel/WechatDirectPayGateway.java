package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.base.AbstractPayGateway;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付 <b>直连商户号</b> 网关（小程序 JSAPI）。接口坐标见 {@link WechatDirectApis}。
 *
 * <h2>与 {@link WechatPayGateway}（电商收付通）互斥装配</h2>
 * 两者的 {@link #payChannel()} 都是 {@code WECHAT} —— 通道名是主数据，
 * 费率、对账轴、商家看到的收银台条目都按它认，不能因为接入方式不同就变成两个「微信」。
 * 靠 {@code shop.pay.wechat.mode} 二选一：{@code direct} 装这个，
 * {@code ecommerce} 装那个。<b>同时装配会在启动时被 {@link PayGatewayRouter} 拦下</b>。
 *
 * <p><b>装配点在 {@link WechatPayChannelConfig}，不是这里的 {@code @Component}。</b>
 * 它要同时满足两个条件（{@code enabled=true} <b>且</b> {@code mode=direct}），
 * 而 {@code @ConditionalOnProperty} <b>不可重复</b> —— 类上叠两个只有一个生效。
 * 叠着的后果是：{@code enabled=false} 时这个 bean 照样被创建，
 * 然后去要一个不存在的 {@code wechatChannelClient}，<b>整个应用起不来</b>。
 * （2026-09-04 实测：1806 条测试里 1638 条因此报 context 加载失败。）
 *
 * <h2>补差与分账在这里一律拒绝，这是有意的</h2>
 * 直连商户号意味着钱进<b>平台自己的商户号</b>（[ADR-017] 的路径 A「归集」）：
 * 平台是销售主体，给商家的是货款（B2B 应付），不是分账。
 *
 * <p>让它们返回成功能让结算链路立刻变绿 —— 而绿的含义是
 * 「平台以为分账发出去了，通道那边什么都没发生」。到对账日之前没有任何症状。
 * <b>不支持就要报错，报错是它在这里唯一的价值。</b>
 */
public class WechatDirectPayGateway extends AbstractPayGateway {

    private static final Logger log = LoggerFactory.getLogger(WechatDirectPayGateway.class);

    /** 本网关只做小程序/公众号 JSAPI。别的方式**显式拒绝**，不静默按 JSAPI 发 */
    static final String PAY_METHOD_JSAPI = "JSAPI";
    static final String DEFAULT_CURRENCY = "CNY";
    /** 微信商品描述上限 127 字符 */
    private static final int DESC_MAX = 127;
    /** 退款原因上限 80 字符 */
    private static final int REASON_MAX = 80;
    /** 直连模式下不可用的动作，统一这一句 */
    private static final String NOT_IN_DIRECT_MODE =
            "微信直连商户号不做%s —— 直连是「归集」路径（ADR-017 路径 A），"
                    + "钱进平台商户号，给商家的是货款不是分账";

    private final ChannelMessageRecorder recorder;
    private final WechatApiV3Signer signer;
    private final String mchId;
    private final String appId;
    private final String notifyUrl;

    public WechatDirectPayGateway(ChannelClient client,
                                  PayChannelMasterService channelMaster,
                                  ChannelMessageRecorder recorder,
                                  WechatApiV3Signer signer,
                                  String mchId, String appId, String notifyUrl) {
        super(client, channelMaster, recorder);
        this.recorder = recorder;
        this.signer = signer;
        this.mchId = mchId;
        this.appId = appId;
        this.notifyUrl = notifyUrl;
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    // ---------------------------------------------------------------- 下单

    /**
     * JSAPI 下单，并把小程序 {@code requestPayment} 要用的六件套算好返回。
     *
     * <p><b>端上拿到什么就传什么</b>（`c-app` 里是 `requestPayment(init.payParams)`），
     * 所以这里的键名必须是微信要的那几个，不能翻译成一套「统一格式」。
     */
    @Override
    public PrepayResult prepay(PrepayCommand cmd) {
        String missing = missingPrecondition(cmd);
        if (missing != null) {
            /*
             * **缺件就不发。** 编一个空 openid 发出去的话，微信返回 PARAM_ERROR，
             * 而端上看到的是「点了没反应」—— 这条链上最难查的一类症状：
             * 订单在、流水在（已关闭）、日志里只有一行 warn。
             */
            log.warn("[wechat-direct] 下单前置不满足，不发通道：{}（单号 {}）", missing, cmd.outTradeNo());
            return PrepayResult.fail(missing);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", appId);
        body.put("mchid", mchId);
        body.put("description", trim(cmd.subject(), DESC_MAX));
        body.put("out_trade_no", cmd.outTradeNo());
        body.put("notify_url", notifyUrl);
        body.put("amount", Map.of(
                "total", cmd.amountMinor(),
                "currency", cmd.currency() == null ? DEFAULT_CURRENCY : cmd.currency()));
        body.put("payer", Map.of("openid", cmd.payerId()));

        try {
            Map<String, Object> resp = client.post(WechatDirectApis.JSAPI, body);
            Object prepayId = resp.get("prepay_id");
            if (prepayId == null) {
                String msg = "微信下单未返回 prepay_id";
                log.warn("[wechat-direct] {}（单号 {}）", msg, cmd.outTradeNo());
                recorder.sent(payChannel(), WechatDirectApis.JSAPI, cmd.outTradeNo(), false, msg, body);
                return PrepayResult.fail(msg);
            }
            recorder.sent(payChannel(), WechatDirectApis.JSAPI, cmd.outTradeNo(), true, null, body);
            log.info("[wechat-direct] 下单成功：单号 {}，金额 {} 分", cmd.outTradeNo(), cmd.amountMinor());
            /*
             * **下单回执里没有 transaction_id** —— 直连 JSAPI 只给 prepay_id。
             * 通道交易号要等回调（或查单）才有，所以这里如实传 null,
             * 不拿 prepay_id 冒充它：对账是按 transaction_id 去通道后台核的,
             * 塞一个查不到的值进去，会让对账每一轮都报一笔不存在的差异。
             */
            return PrepayResult.ok(jsapiParams(String.valueOf(prepayId)), null);
        } catch (ChannelClient.ChannelException e) {
            log.warn("[wechat-direct] 下单失败：{}（单号 {}，可重试 {}）",
                    e.getMessage(), cmd.outTradeNo(), e.isRetryable());
            recorder.sent(payChannel(), WechatDirectApis.JSAPI, cmd.outTradeNo(), false,
                    e.getMessage(), body);
            return PrepayResult.fail(e.getMessage());
        }
    }

    /** 缺哪一件就说哪一件 —— 「下单失败」这四个字排查时一点用都没有。 */
    private String missingPrecondition(PrepayCommand cmd) {
        if (appId == null || appId.isBlank()) {
            return "微信小程序 appid 未配置（shop.pay.wechat.appid）";
        }
        if (notifyUrl == null || notifyUrl.isBlank()) {
            /*
             * **回调地址缺了必须拦在这里。** 微信允许不带 notify_url 吗？不允许 ——
             * 但更要紧的是：就算发出去成功了，用户付的钱我方永远收不到通知,
             * 那笔单停在 PENDING，只能等对账日。
             */
            return "微信支付回调地址未配置（shop.pay.wechat.notify-url）";
        }
        if (cmd.payerId() == null || cmd.payerId().isBlank()) {
            return "拿不到用户的小程序 openid —— JSAPI 下单必须有 payer.openid";
        }
        if (cmd.payMethod() != null && !PAY_METHOD_JSAPI.equals(cmd.payMethod())) {
            // 静默按 JSAPI 发的话，App 用户会拿到一组小程序才用得了的参数
            return "微信直连网关当前只支持 JSAPI，收到的是：" + cmd.payMethod();
        }
        if (cmd.amountMinor() <= 0) {
            return "金额必须大于 0";
        }
        return null;
    }

    /**
     * 小程序 {@code wx.requestPayment} 的六件套。
     *
     * <p>{@code package} 是 {@code prepay_id=} <b>带前缀的整串</b>，
     * 且 paySign 签的也是这个整串 —— 传裸 prepay_id 的话端上报
     * 「支付验证签名失败」，而后端一切正常。
     */
    private Map<String, String> jsapiParams(String prepayId) {
        String timeStamp = String.valueOf(System.currentTimeMillis() / 1000);
        String nonceStr = WechatApiV3Signer.nonce();
        String pkg = "prepay_id=" + prepayId;
        String paySign = signer.jsapiPaySign(appId, timeStamp, nonceStr, pkg);
        /*
         * **把端上要用的六件套记一行，并当场自验一次签名。**
         *
         * 端上报「支付验证签名失败」时，第一个要回答的是「是我们签错了，还是对方不认」——
         * 这两件事的下一步完全不同。打印 paySign 回答不了它（一串 base64 看上去永远是对的），
         * 所以这里打的是**自验结果**：用从私钥推出的公钥验回自己刚签的名。
         *
         * 自验通过 ⇒ 签名与待签串是自洽的，问题在内容或对方那边；
         * 自验失败 ⇒ 私钥/算法这一层就错了，与微信无关。
         *
         * paySign 本身**不打**：它没有长期价值，而日志会被采集、转发、贴进工单。
         * 其余五项都不是机密（本来就要原样发给端上），而它们正是排查要对的东西。
         */
        log.info("[wechat-direct] 端上六件套 appId={} timeStamp={} nonceStr={} package={} signType={} paySign自验={}",
                appId, timeStamp, nonceStr, pkg, WechatApiV3Signer.SIGN_TYPE,
                signer.verifyOwnSignature(
                        WechatApiV3Signer.paySignContent(appId, timeStamp, nonceStr, pkg), paySign)
                        ? "通过" : "**失败**");
        return Map.of(
                "appId", appId,
                "timeStamp", timeStamp,
                "nonceStr", nonceStr,
                "package", pkg,
                "signType", WechatApiV3Signer.SIGN_TYPE,
                "paySign", paySign);
    }

    // ---------------------------------------------------------------- 查单

    /**
     * 查单。<b>GET，且 {@code ?mchid=} 要参与签名</b>。
     *
     * <p>只有 {@code trade_state=SUCCESS} 算已支付；
     * 查询失败与「通道没有这笔」是两件事 —— 前者不能关单，后者可以。
     */
    @Override
    public QueryResult query(String outTradeNo) {
        try {
            Map<String, Object> resp = client.get(
                    WechatDirectApis.TRANSACTION_BY_OUT_TRADE_NO + outTradeNo + "?mchid=" + mchId);
            Object state = resp.get("trade_state");
            if (state == null) {
                return QueryResult.notFound();
            }
            if (!"SUCCESS".equals(state)) {
                return QueryResult.unpaid();
            }
            return QueryResult.paid(totalOf(resp), String.valueOf(resp.get("transaction_id")));
        } catch (ChannelClient.ChannelException e) {
            /*
             * **只有 ORDER_NOT_EXIST 才是「通道没有这笔」**，其余失败一律 failed()。
             * 把「查询失败」当成 notFound 的话，对账会把一批可能已付的单关掉。
             */
            if (notExist(e.getMessage(), "ORDER_NOT_EXIST")) {
                return QueryResult.notFound();
            }
            log.warn("[wechat-direct] 查单失败，不据此关单：{}（{}）", outTradeNo, e.getMessage());
            return QueryResult.failed();
        }
    }

    /**
     * 查退款。<b>与查单是两套单据</b>：拿退款单号去查收款接口，通道会说「没有这笔」，
     * 而对账把「通道说没有」当作可以安全关单的依据 —— 于是待确认的退款被批量关掉，
     * 而钱可能真的已经退出去了。
     */
    @Override
    public QueryResult queryRefund(String outRefundNo) {
        try {
            Map<String, Object> resp = client.get(
                    WechatDirectApis.REFUND_BY_OUT_REFUND_NO + outRefundNo);
            Object status = resp.get("status");
            if (status == null) {
                return QueryResult.notFound();
            }
            // 退款的「已完成」是 SUCCESS；PROCESSING / ABNORMAL / CLOSED 都不是
            if (!"SUCCESS".equals(status)) {
                return QueryResult.unpaid();
            }
            return QueryResult.paid(refundAmountOf(resp), String.valueOf(resp.get("refund_id")));
        } catch (ChannelClient.ChannelException e) {
            if (notExist(e.getMessage(), "RESOURCE_NOT_EXISTS")) {
                return QueryResult.notFound();
            }
            log.warn("[wechat-direct] 查退款失败，不据此关单：{}（{}）", outRefundNo, e.getMessage());
            return QueryResult.failed();
        }
    }

    /**
     * 通道的错误码是不是「确实没有这笔」。
     *
     * <h2>2026-09-04：这里原来写错了，而且错得很安静</h2>
     * 原本判的是 {@code ORDERNOTEXIST}（无下划线，抄自旧文档），
     * <b>而真微信返回的是 {@code ORDER_NOT_EXIST}</b> —— 拿一个不存在的单号
     * 打真接口实测出来的。拼法对不上的后果不是报错，是
     * <b>「通道确实没有这笔」被降级成「查询失败」</b>：
     * 对账那条轴于是永远关不掉这种单，每一轮查一次、每一轮判不了，
     * 而它本该在第一轮就安全关掉。
     *
     * <p>去掉下划线再比，两种拼法都认 —— 通道改文案的成本比我们再吃一次这个亏低。
     *
     * @param known 实测确认的错误码（查单 {@code ORDER_NOT_EXIST}、
     *              查退款 {@code RESOURCE_NOT_EXISTS}，均 2026-09-04 打真接口验过）
     */
    private static boolean notExist(String message, String known) {
        return norm(message).contains(norm(known));
    }

    private static String norm(String s) {
        return String.valueOf(s).replace("_", "");
    }

    private static long totalOf(Map<String, Object> resp) {
        return resp.get("amount") instanceof Map<?, ?> m && m.get("total") instanceof Number n
                ? n.longValue() : 0L;
    }

    private static long refundAmountOf(Map<String, Object> resp) {
        return resp.get("amount") instanceof Map<?, ?> m && m.get("refund") instanceof Number n
                ? n.longValue() : 0L;
    }

    // ---------------------------------------------------------------- 五个资金动作

    @Override
    protected Call buildRefund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        /*
         * transaction_id 与 out_trade_no **二选一**。优先通道交易号 ——
         * 我方单号在重试时带后缀，而退的是原来那一笔。
         */
        if (ctx.tradeNo() != null && !ctx.tradeNo().isBlank()) {
            body.put("transaction_id", ctx.tradeNo());
        } else {
            body.put("out_trade_no", ctx.outTradeNo());
        }
        body.put("out_refund_no", requestNo);
        body.put("reason", trim(reason, REASON_MAX));
        /*
         * **退款也要带 notify_url。** 不带的话微信会退回用「商户平台上配的那个」——
         * 而那是个我方代码控制不了的地址：谁在后台改一下，退款结果就再也回不来，
         * 且没有任何地方会报错，只有退款一直停在「处理中」。
         */
        if (notifyUrl != null && !notifyUrl.isBlank()) {
            body.put("notify_url", notifyUrl + "/refund");
        }
        body.put("amount", Map.of(
                "refund", amountMinor,
                "total", ctx.totalMinor(),
                "currency", DEFAULT_CURRENCY));
        return new Call(WechatDirectApis.REFUND, body, "refund_id");
    }

    @Override
    protected Call buildSubsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        return reject(NOT_IN_DIRECT_MODE.formatted("补差"));
    }

    @Override
    protected Call buildSubsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        return reject(NOT_IN_DIRECT_MODE.formatted("补差回退"));
    }

    @Override
    protected Call buildSplit(TxContext ctx, long amountMinor, String requestNo) {
        return reject(NOT_IN_DIRECT_MODE.formatted("分账"));
    }

    @Override
    protected Call buildSplitReverse(TxContext ctx, long amountMinor, String requestNo) {
        return reject(NOT_IN_DIRECT_MODE.formatted("分账回退"));
    }

    /**
     * 直连 APIv3 的成功判据是 <b>HTTP 2xx</b> —— 失败已经由
     * {@link WechatChannelClient} 转成异常。这里只兜住「2xx 里带错误码」这种反常情况。
     */
    @Override
    protected String failureOf(String api, Map<String, Object> resp) {
        Object code = resp.get("code");
        return code == null ? null : api + " 返回 code=" + code;
    }
}
