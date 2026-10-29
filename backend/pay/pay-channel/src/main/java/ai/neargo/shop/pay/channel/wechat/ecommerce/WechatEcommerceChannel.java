package ai.neargo.shop.pay.channel.wechat.ecommerce;

import ai.neargo.shop.pay.channel.PaymentAccountLookup;
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
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.capability.OnlinePayCapability;
import ai.neargo.shop.pay.channel.capability.SplitCapability;
import ai.neargo.shop.pay.channel.capability.TransactionCapability;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.pay.channel.wechat.WechatResults;
import ai.neargo.shop.pay.channel.wechat.WechatV3Client;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 微信支付<b>电商收付通</b>（{@code SERVICE_PROVIDER}，ADR-017 路径 B：钱进二级商户、平台分账）（TDD-支付服务-W2渠道层补完 S5）。
 * 报文自原 {@code WechatPayGateway} 逐字搬来。
 *
 * <p>五个资金动作的时序：<b>支付 → 补差 → 分账</b>，退款反着来 <b>分账回退 → 补差回退 → 退款</b>。顺序由调用方负责；
 * 写反的表现是通道报错，而那时钱已经在二级商户的冻结账户里了。
 *
 * <p><b>下单（合单 JSAPI）还没接</b>：与旧网关同样明确失败、不发渠道（旧网关的默认实现）。声明线上收款能力是因为产品表里
 * {@code WECHAT_JSAPI_SP} 开着 —— 不声明，启动校验会因「开着的产品没有收款处理器」拦下收付通部署。
 */
public final class WechatEcommerceChannel implements OnlinePayCapability, TransactionCapability, SplitCapability {

    static final String REFUND = "/v3/ecommerce/refunds/apply";
    static final String PARTNER_TRANSACTION_BY_OUT_TRADE_NO = "/v3/pay/partner/transactions/out-trade-no/";
    static final String ECOMMERCE_REFUND_BY_OUT_REFUND_NO = "/v3/ecommerce/refunds/out-refund-no/";
    static final String SUBSIDY_CREATE = "/v3/ecommerce/subsidies/create";
    static final String SUBSIDY_RETURN = "/v3/ecommerce/subsidies/return";
    static final String PROFIT_SHARING = "/v3/ecommerce/profitsharing/orders";
    static final String PROFIT_SHARING_RETURN = "/v3/ecommerce/profitsharing/returnorders";
    private static final int DESC_MAX = 80;

    /** 服务商商户号（sp_mchid） */
    private final WechatV3Client client;
    private final String spMchId;
    private final PaymentAccountLookup accounts;
    /** {@code sys_pay_channel.supports_subsidy}：0 时补差<b>绝不能发</b>（该通道整个不开积分抵扣） */
    private final Predicate<String> subsidyAllowed;

    public WechatEcommerceChannel(WechatV3Client client, String spMchId, PaymentAccountLookup accounts,
                                  Predicate<String> subsidyAllowed) {
        this.client = client;
        this.spMchId = spMchId;
        this.accounts = accounts;
        this.subsidyAllowed = subsidyAllowed;
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    @Override
    public AccessMode accessMode() {
        return AccessMode.SERVICE_PROVIDER;
    }

    @Override
    public Set<PayScene> onlineScenes() {
        return EnumSet.of(PayScene.ONLINE_SDK);
    }

    @Override
    public Set<ChannelOperation> transactionOperations() {
        return EnumSet.of(ChannelOperation.QUERY, ChannelOperation.CLOSE, ChannelOperation.REFUND,
                ChannelOperation.REFUND_QUERY);
    }

    @Override
    public Set<SplitCommand.Kind> splitKinds() {
        return EnumSet.allOf(SplitCommand.Kind.class);
    }

    @Override
    public ChannelResult payOnline(Invocation inv, PayCommand c) {
        // 「没实现」必须表现为失败，不能表现为成功：假参数会让端上唤起一个付不了的收银台
        return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, "LOCAL_PAYMENT_NOT_SENT",
                "WECHAT 收付通尚未实现向通道下单", false);
    }

    /**
     * 服务商路径，必须带 sp_mchid 与这笔交易的 sub_mchid（F12 · Review R14）。不知道子商户号就不查，按「查询失败」处理
     * （对账留到下一轮）—— 不能退化成「通道没有这笔」，那会被当成可以安全关单。
     */
    @Override
    public ChannelResult query(Invocation inv, QueryCommand c) {
        String sub = subMerchant(inv, c.outTradeNo());
        if (sub == null || spMchId == null || spMchId.isBlank()) {
            return new ChannelResult.Unknown("SUB_MERCHANT_UNKNOWN");
        }
        return WechatResults.trade(c.outTradeNo(), client.get(inv, PARTNER_TRANSACTION_BY_OUT_TRADE_NO + c.outTradeNo()
                + "?sp_mchid=" + spMchId + "&sub_mchid=" + sub));
    }

    @Override
    public ChannelResult close(Invocation inv, CloseCommand c) {
        String sub = subMerchant(inv, c.outTradeNo());
        if (sub == null) {
            return new ChannelResult.Failed(PayErrorCode.PARAM_INVALID, null, "不知道这笔交易的子商户号，不能关单", false);
        }
        client.post(inv, PARTNER_TRANSACTION_BY_OUT_TRADE_NO + c.outTradeNo() + "/close",
                Map.of("sp_mchid", spMchId, "sub_mchid", sub));
        return new ChannelResult.Succeeded(null, null, null, Map.of());
    }

    /** 单笔最多部分退款 50 次，多次需换退款单号；两次调用间隔 ≥ 60 秒（管线的退款间隔环按产品配置排队） */
    @Override
    public ChannelResult refund(Invocation inv, RefundCommand c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", inv.account().subMerchantId());
        body.put("transaction_id", c.originChannelTradeNo());
        body.put("out_refund_no", c.outRefundNo());
        body.put("reason", WechatResults.trim(c.reason(), DESC_MAX));
        body.put("amount", Map.of("refund", c.refundAmount().minor(), "total", c.originAmount().minor(),
                "currency", c.refundAmount().currency()));
        return WechatResults.accepted(client.post(inv, REFUND, body), "refund_id");
    }

    /**
     * 收付通退款接口，带 sub_mchid（F12 · Review R14）。与旧网关同口径：没有 {@code status} → 没有这笔；SUCCESS → 退成；
     * 其余（PROCESSING / ABNORMAL / CLOSED）都是「有这笔、还没退成」—— 收付通的五态细分随 I3 退款单拆分再做。
     */
    @Override
    public ChannelResult queryRefund(Invocation inv, RefundQueryCommand c) {
        String sub = subMerchant(inv, c.outRefundNo());
        if (sub == null) {
            return new ChannelResult.Unknown("SUB_MERCHANT_UNKNOWN");
        }
        Map<String, Object> resp = client.get(inv, ECOMMERCE_REFUND_BY_OUT_REFUND_NO + c.outRefundNo()
                + "?sub_mchid=" + sub);
        Object status = resp.get("status");
        if (status == null) {
            return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "渠道没有这笔退款", false);
        }
        String provider = resp.get("refund_id") instanceof String id ? id : null;
        if (!"SUCCESS".equals(status)) {
            return new ChannelResult.Pending(PayInstruction.none(), provider, null);
        }
        long refunded = WechatResults.refundAmountOf(resp);
        String currency = WechatResults.currencyOf(resp);
        return new ChannelResult.Succeeded(provider, currency == null ? null : new Money(refunded, currency), null,
                WechatResults.extra(c.outRefundNo(), null, refunded));
    }

    // ---------------------------------------------------------------- 补差与分账

    @Override
    public ChannelResult split(Invocation inv, SplitCommand c) {
        if (c.kind() == SplitCommand.Kind.SUBSIDY && !subsidyAllowed.test(payChannel())) {
            // 能力位拦下来的<b>不可重试</b> —— 重试一万次通道也不会长出这个能力
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null,
                    payChannel() + " 不支持补差（sys_pay_channel.supports_subsidy=0）", false);
        }
        String sub = c.subMerchantId() != null ? c.subMerchantId() : inv.account().subMerchantId();
        Map<String, Object> body = new LinkedHashMap<>();
        return switch (c.kind()) {
            case SUBSIDY -> {
                // 时序硬约束：订单支付成功并结算完成后、发起分账前
                body.put("sub_mchid", sub);
                body.put("transaction_id", c.channelTradeNo());
                body.put("amount", c.amount().minor());
                body.put("description", WechatResults.trim(c.description(), DESC_MAX));
                body.put("out_subsidy_no", c.requestNo());
                yield WechatResults.splitDone(client.post(inv, SUBSIDY_CREATE, body), "subsidy_id", c.amount());
            }
            case SUBSIDY_RETURN -> {
                body.put("sub_mchid", sub);
                body.put("subsidy_id", c.channelTradeNo());
                body.put("out_order_no", c.requestNo());
                body.put("amount", c.amount().minor());
                body.put("description", WechatResults.trim(c.description(), DESC_MAX));
                yield WechatResults.splitDone(client.post(inv, SUBSIDY_RETURN, body), "refund_id", c.amount());
            }
            case SPLIT -> {
                // 单笔最多分账 50 次、每次最多 50 个接收方；我们一次只分给平台一个接收方
                body.put("sub_mchid", sub);
                body.put("transaction_id", c.channelTradeNo());
                body.put("out_order_no", c.requestNo());
                body.put("receivers", List.of(Map.of("type", "MERCHANT_ID", "amount", c.amount().minor(),
                        "description", "平台服务费")));
                body.put("finish", false);
                yield WechatResults.splitDone(client.post(inv, PROFIT_SHARING, body), "order_id", c.amount());
            }
            case SPLIT_REVERSE -> {
                // 部分回退，不是全额回退再重分 —— 微信回退后不支持重新发起分账
                body.put("sub_mchid", sub);
                body.put("order_id", c.channelTradeNo());
                body.put("out_return_no", c.requestNo());
                body.put("amount", c.amount().minor());
                body.put("description", "退款回退");
                yield WechatResults.splitDone(client.post(inv, PROFIT_SHARING_RETURN, body), "return_id", c.amount());
            }
        };
    }

    /** 上下文带了就用（收款商户路由），否则按我方单号查这笔交易实际进的子商户号（存量调用方只给单号） */
    private String subMerchant(Invocation inv, String outNo) {
        String fromContext = inv.account().subMerchantId();
        if (fromContext != null && !fromContext.isBlank()) {
            return fromContext;
        }
        String looked = accounts.subMerchantOf(outNo).orElse(null);
        return looked == null || looked.isBlank() ? null : looked;
    }
}
