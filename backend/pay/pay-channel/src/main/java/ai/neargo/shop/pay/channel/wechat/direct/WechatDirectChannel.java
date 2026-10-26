package ai.neargo.shop.pay.channel.wechat.direct;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.PayScene;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.PayCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.capability.OnlinePayCapability;
import ai.neargo.shop.pay.channel.capability.TransactionCapability;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.wechat.WechatResults;
import ai.neargo.shop.pay.channel.wechat.WechatV3Client;
import ai.neargo.shop.pay.channel.wechat.WechatV3Protocol;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.net.URI;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 微信支付<b>直连商户号</b>（{@code MERCHANT_OWN}，ADR-017 路径 A「归集」）：小程序 JSAPI 收款、查单、关单、退款、查退款
 * （TDD-支付服务-W2渠道层补完 S5）。报文与结果判据自原 {@code WechatDirectPayGateway} 逐字搬来。
 *
 * <p><b>不实现 {@code SplitCapability}</b>：直连的钱进平台商户号，给商家的是货款（B2B 应付），不是分账。
 * 让补差 / 分账在路由这一步就明确失败 —— 返回成功能让结算链路立刻变绿，而绿的含义是「平台以为分账发出去了，通道那边什么都没发生」。
 *
 * <p>与收付通（{@code WechatEcommerceChannel}）是<b>两种接入模式的两个处理器</b>，键里的接入模式不同，不再靠
 * 「装的是哪个类」区分；同一部署装哪一个仍由 {@code shop.pay.wechat.mode} 决定（凭据是一套）。
 */
public final class WechatDirectChannel implements OnlinePayCapability, TransactionCapability {

    /** 直连商户号的接口坐标（与收付通 {@code /v3/ecommerce/*} 是两套，混用返回 NO_AUTH） */
    static final String JSAPI = "/v3/pay/transactions/jsapi";
    static final String TRANSACTION_BY_OUT_TRADE_NO = "/v3/pay/transactions/out-trade-no/";
    static final String REFUND = "/v3/refund/domestic/refunds";
    static final String REFUND_BY_OUT_REFUND_NO = "/v3/refund/domestic/refunds/";
    private static final String DEFAULT_CURRENCY = "CNY";
    /** 微信商品描述上限 127 字符；退款原因上限 80 字符 */
    private static final int DESC_MAX = 127;
    private static final int REASON_MAX = 80;

    private final WechatV3Client client;
    private final WechatV3Protocol protocol;
    private final String mchId;
    private final String appId;
    private final String notifyUrl;
    /** 退款结果回调地址：显式配了用它，否则由 {@link #notifyUrl} 推导（F13 · Review R32） */
    private final String refundNotifyUrl;

    public WechatDirectChannel(WechatV3Client client, WechatV3Protocol protocol, String mchId, String appId,
                               String notifyUrl, String refundNotifyUrl) {
        this.client = client;
        this.protocol = protocol;
        this.mchId = mchId;
        this.appId = appId;
        this.notifyUrl = notifyUrl;
        this.refundNotifyUrl = refundNotifyUrl != null && !refundNotifyUrl.isBlank()
                ? refundNotifyUrl : refundUrlOf(notifyUrl);
    }

    /**
     * 由支付回调地址推导退款回调地址：路径末段后加 {@code refund}，查询参数原样留在后面。
     * 此前是 {@code notifyUrl + "/refund"} —— 带结尾斜杠就成了 {@code …//refund}，带查询参数就成了 {@code …?env=prod/refund}。
     */
    public static String refundUrlOf(String notifyUrl) {
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return null;
        }
        URI u = URI.create(notifyUrl.trim());
        String path = u.getRawPath() == null ? "" : u.getRawPath();
        String refundPath = (path.endsWith("/") ? path : path + "/") + "refund";
        return u.getScheme() + "://" + u.getRawAuthority() + refundPath
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    @Override
    public AccessMode accessMode() {
        return AccessMode.MERCHANT_OWN;
    }

    @Override
    public Set<PayScene> onlineScenes() {
        // 小程序 / 公众号 JSAPI。NATIVE（二维码）、H5、APP 没接：声明了就会被收银台列出来
        return EnumSet.of(PayScene.ONLINE_SDK);
    }

    @Override
    public Set<ChannelOperation> transactionOperations() {
        return EnumSet.of(ChannelOperation.QUERY, ChannelOperation.CLOSE, ChannelOperation.REFUND,
                ChannelOperation.REFUND_QUERY);
    }

    // ---------------------------------------------------------------- 下单

    /**
     * JSAPI 下单，回端上小程序 {@code requestPayment} 六件套。<b>缺件就不发</b>：编一个空 openid 发出去，微信返回 PARAM_ERROR，
     * 而端上看到的是「点了没反应」。回执没有 {@code prepay_id} = 渠道收没收下说不清，按不明交补查。
     * 下单回执里没有 {@code transaction_id}（要等回调或查单），通道交易号如实为空，不拿 prepay_id 冒充。
     */
    @Override
    public ChannelResult payOnline(Invocation inv, PayCommand c) {
        String missing = missingPrecondition(c);
        if (missing != null) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null, missing, false);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", appId);
        body.put("mchid", mchId);
        body.put("description", WechatResults.trim(c.subject(), DESC_MAX));
        body.put("out_trade_no", c.outTradeNo());
        body.put("notify_url", notifyUrl);
        body.put("amount", Map.of("total", c.amount().minor(), "currency", c.amount().currency()));
        body.put("payer", Map.of("openid", c.payerId()));
        Map<String, Object> resp = client.post(inv, JSAPI, body);
        Object prepayId = resp.get("prepay_id");
        if (prepayId == null) {
            return new ChannelResult.Unknown("PREPAY_ID_MISSING: 微信下单未返回 prepay_id");
        }
        Map<String, String> six = protocol.jsapiParams(appId, String.valueOf(prepayId));
        return new ChannelResult.Pending(new PayInstruction.JsapiParams(six), null,
                protocol.selfCheck(six) ? "PAYSIGN_SELF_CHECK_OK" : "PAYSIGN_SELF_CHECK_FAILED");
    }

    /** 缺哪一件就说哪一件 —— 「下单失败」这四个字排查时一点用都没有 */
    private String missingPrecondition(PayCommand c) {
        if (appId == null || appId.isBlank()) {
            return "微信小程序 appid 未配置（shop.pay.wechat.appid）";
        }
        if (notifyUrl == null || notifyUrl.isBlank()) {
            return "微信支付回调地址未配置（shop.pay.wechat.notify-url）";
        }
        if (c.payerId() == null || c.payerId().isBlank()) {
            return "拿不到用户的小程序 openid —— JSAPI 下单必须有 payer.openid";
        }
        if (c.amount().minor() <= 0) {
            return "金额必须大于 0";
        }
        return null;
    }

    // ---------------------------------------------------------------- 查单 / 关单

    /**
     * GET，且 {@code ?mchid=} 要参与签名。只有 {@code trade_state=SUCCESS} 算已支付（USERPAYING 当成已付会给没付的单发货）。
     * {@code ORDER_NOT_EXIST} 由 {@code WechatErrorMapper} 判成「没有这笔」；其余失败交分类器 —— 查询失败绝不当成没有这笔。
     */
    @Override
    public ChannelResult query(Invocation inv, QueryCommand c) {
        Map<String, Object> resp = client.get(inv, TRANSACTION_BY_OUT_TRADE_NO + c.outTradeNo() + "?mchid=" + mchId);
        return WechatResults.trade(resp);
    }

    @Override
    public ChannelResult close(Invocation inv, CloseCommand c) {
        client.post(inv, TRANSACTION_BY_OUT_TRADE_NO + c.outTradeNo() + "/close", Map.of("mchid", mchId));
        return new ChannelResult.Succeeded(null, null, null, Map.of());
    }

    // ---------------------------------------------------------------- 退款 / 查退款

    /**
     * 直连退款路径（不是 {@code /v3/ecommerce/refunds/apply}）。{@code transaction_id} 与 {@code out_trade_no} 二选一，
     * 优先通道交易号 —— 我方单号在重试时带后缀，而退的是原来那一笔。<b>退款也带 notify_url</b>：不带的话微信退回商户平台上配的那个，
     * 而那是我方代码控制不了的地址。
     */
    @Override
    public ChannelResult refund(Invocation inv, RefundCommand c) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (c.originChannelTradeNo() != null && !c.originChannelTradeNo().isBlank()) {
            body.put("transaction_id", c.originChannelTradeNo());
        } else {
            body.put("out_trade_no", c.originOutTradeNo());
        }
        body.put("out_refund_no", c.outRefundNo());
        body.put("reason", WechatResults.trim(c.reason(), REASON_MAX));
        if (refundNotifyUrl != null) {
            body.put("notify_url", refundNotifyUrl);
        }
        body.put("amount", Map.of("refund", c.refundAmount().minor(), "total", c.originAmount().minor(),
                "currency", c.refundAmount().currency()));
        return WechatResults.accepted(client.post(inv, REFUND, body), "refund_id");
    }

    /**
     * 查退款：与查单是两套单据。{@code SUCCESS} 退成；{@code PROCESSING} 处理中；{@code CLOSED} 渠道关闭（终态，带退款单号与金额）；
     * {@code ABNORMAL} 转人工；没见过的状态与「回的是别人的退款单」一律不明。退款单不存在<b>不</b>判成「没有这笔」——
     * 不能证明从未受理。
     */
    @Override
    public ChannelResult queryRefund(Invocation inv, RefundQueryCommand c) {
        Map<String, Object> resp = client.get(inv, REFUND_BY_OUT_REFUND_NO + c.outRefundNo());
        if (resp.get("out_refund_no") != null && !c.outRefundNo().equals(resp.get("out_refund_no"))) {
            return new ChannelResult.Unknown("REFUND_IDENTITY_MISMATCH");
        }
        String raw = resp.get("status") instanceof String s ? s : null;
        String provider = resp.get("refund_id") instanceof String s ? s : null;
        String currency = WechatResults.currencyOf(resp);
        long refunded = WechatResults.refundAmountOf(resp);
        Money evidence = currency == null ? null : new Money(refunded, currency);
        return switch (raw == null ? "" : raw) {
            case "SUCCESS" -> new ChannelResult.Succeeded(provider, evidence, null,
                    WechatResults.extra(c.outRefundNo(), raw, refunded));
            case "PROCESSING" -> new ChannelResult.Pending(PayInstruction.none(), provider, raw);
            case "CLOSED" -> new ChannelResult.Failed(PayErrorCode.REFUND_CLOSED, raw, "退款已被渠道关闭", false,
                    provider, evidence);
            case "ABNORMAL" -> new ChannelResult.Failed(PayErrorCode.REFUND_ABNORMAL, raw, "退款异常，需人工处理", false,
                    provider, null);
            default -> new ChannelResult.Unknown("REFUND_STATUS_UNKNOWN: " + raw);
        };
    }
}
