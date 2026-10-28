package ai.neargo.shop.pay.channel.alipay;

import ai.neargo.shop.pay.channel.api.AccessMode;
import ai.neargo.shop.pay.channel.api.ChannelOperation;
import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.pay.channel.api.command.CloseCommand;
import ai.neargo.shop.pay.channel.api.command.QueryCommand;
import ai.neargo.shop.pay.channel.api.command.RefundCommand;
import ai.neargo.shop.pay.channel.api.command.RefundQueryCommand;
import ai.neargo.shop.pay.channel.api.command.SplitCommand;
import ai.neargo.shop.pay.channel.capability.ChannelCapability;
import ai.neargo.shop.pay.channel.capability.SplitCapability;
import ai.neargo.shop.pay.channel.capability.TransactionCapability;
import ai.neargo.shop.pay.channel.invoke.Invocation;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 支付宝<b>直付通</b>（{@code SERVICE_PROVIDER}）：查单、退款、补差、分账及回退（TDD-支付服务-W2渠道层补完 S5）。
 * 报文自原 {@code AlipayPayGateway} 逐字搬来；那个网关依赖的 {@code alipayChannelClient} 全仓从没实现过，现在由
 * {@link AlipayProtocol} + 记录型客户端补上。
 *
 * <p>收款下单（App 支付串）与查退款还没接：产品 {@code ALIPAY_APP_SP} 在库里是停用的，启动校验不会要它。
 *
 * <p>⚠️ <b>营销补差的接口名未经文档确认</b>（推断为「反向的分账」，见 {@link #SUBSIDY}）：联调第一件事就是验证它 ——
 * 失败应是「参数错误」而不是「服务不存在」，后者说明接口名本身不对。
 */
public final class AlipayChannel implements TransactionCapability, SplitCapability {

    static final String TRADE_QUERY = "alipay.trade.query";
    static final String TRADE_CLOSE = "alipay.trade.close";
    static final String REFUND = "alipay.trade.refund";
    static final String ORDER_SETTLE = "alipay.trade.order.settle";
    /** 营销补差 = 反向的分账（trans_out 平台 → trans_in 二级商户）—— 推断，未经文档确认 */
    static final String SUBSIDY = ORDER_SETTLE;
    private static final String OK = "10000";
    private static final String CURRENCY = "CNY";

    private final AlipayClient client;
    /** 平台自己的收款账号：补差时作为出资方（trans_out），分账时作为收款方。没配时补差明确失败而不是静默补 0 */
    private final String platformAccount;
    private final Predicate<String> subsidyAllowed;

    public AlipayChannel(AlipayClient client, String platformAccount, Predicate<String> subsidyAllowed) {
        this.client = client;
        this.platformAccount = platformAccount == null ? "" : platformAccount;
        this.subsidyAllowed = subsidyAllowed;
    }

    @Override
    public String payChannel() {
        return "ALIPAY";
    }

    @Override
    public AccessMode accessMode() {
        return AccessMode.SERVICE_PROVIDER;
    }

    @Override
    public Set<ChannelOperation> transactionOperations() {
        return EnumSet.of(ChannelOperation.QUERY, ChannelOperation.CLOSE, ChannelOperation.REFUND);
    }

    @Override
    public Set<SplitCommand.Kind> splitKinds() {
        return EnumSet.allOf(SplitCommand.Kind.class);
    }

    /**
     * {@code trade_status}：TRADE_SUCCESS / TRADE_FINISHED 算已支付（后者是已完成不可退，钱同样收到了）；其余是有这笔未付。
     * {@code ACQ.TRADE_NOT_EXIST} 才是「没有这笔」；别的业务错误不能当成没有这笔，也不能当成未付 —— 按不明再查。
     * 金额是元（字符串），解析不出来不当已支付。
     */
    @Override
    public ChannelResult query(Invocation inv, QueryCommand c) {
        Map<String, Object> resp = client.call(inv, TRADE_QUERY, Map.of("out_trade_no", c.outTradeNo()));
        if (!OK.equals(String.valueOf(resp.get("code")))) {
            String sub = String.valueOf(resp.get("sub_code"));
            if (sub.contains("TRADE_NOT_EXIST")) {
                return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, sub, "渠道没有这笔", false);
            }
            return new ChannelResult.Unknown("QUERY_FAILED: code=" + resp.get("code") + " sub_code=" + sub);
        }
        String tradeNo = resp.get("trade_no") == null ? null : String.valueOf(resp.get("trade_no"));
        Object status = resp.get("trade_status");
        if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
            return new ChannelResult.Pending(PayInstruction.none(), tradeNo, status == null ? null : String.valueOf(status));
        }
        try {
            long minor = new BigDecimal(String.valueOf(resp.get("total_amount"))).movePointRight(2).longValueExact();
            return new ChannelResult.Succeeded(tradeNo, new Money(minor, CURRENCY), null, Map.of());
        } catch (ArithmeticException | NumberFormatException e) {
            return new ChannelResult.Unknown("AMOUNT_UNPARSEABLE: " + resp.get("total_amount"));
        }
    }

    @Override
    public ChannelResult close(Invocation inv, CloseCommand command) {
        Map<String, Object> response = client.call(inv, TRADE_CLOSE, Map.of("out_trade_no", command.outTradeNo()));
        if (OK.equals(String.valueOf(response.get("code")))
                && command.outTradeNo().equals(response.get("out_trade_no"))) {
            return new ChannelResult.Succeeded(idOf(response), null, null, Map.of());
        }
        // 非成功、缺件或状态冲突均不能证明已关；调用方按原交易查回，不编造关闭事实。
        return new ChannelResult.Unknown("CLOSE_UNPROVEN: " + failure(TRADE_CLOSE, response));
    }

    /** 多次退款<b>必须传不同的 {@code out_request_no}</b>（我方退款单号已保证唯一） */
    @Override
    public ChannelResult refund(Invocation inv, RefundCommand c) {
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("trade_no", c.originChannelTradeNo());
        biz.put("out_trade_no", c.originOutTradeNo());
        biz.put("out_request_no", c.outRefundNo());
        biz.put("refund_amount", yuan(c.refundAmount().minor()));
        biz.put("refund_reason", c.reason());
        Map<String, Object> resp = client.call(inv, REFUND, biz);
        if (!OK.equals(String.valueOf(resp.get("code")))) {
            return new ChannelResult.Unknown("REFUND_SEND_UNPROVEN: " + failure(REFUND, resp));
        }
        String id = idOf(resp);
        return id == null ? new ChannelResult.Unknown("REFUND_SEND_UNKNOWN: 回执未返回 trade_no")
                : new ChannelResult.Pending(PayInstruction.none(), id);
    }

    @Override
    public ChannelResult queryRefund(Invocation inv, RefundQueryCommand command) {
        return ChannelCapability.unsupported(ChannelOperation.REFUND_QUERY);
    }

    @Override
    public ChannelResult split(Invocation inv, SplitCommand c) {
        String sub = c.subMerchantId() != null ? c.subMerchantId() : inv.account().subMerchantId();
        String method;
        Map<String, Object> biz;
        switch (c.kind()) {
            case SUBSIDY -> {
                if (!subsidyAllowed.test(payChannel())) {
                    return reject(payChannel() + " 不支持补差（sys_pay_channel.supports_subsidy=0）");
                }
                if (platformAccount.isBlank()) {
                    return reject("未配置 shop.pay.alipay.platform-account，补差没有出资方");
                }
                method = SUBSIDY;
                biz = royalty(c, platformAccount, sub, c.description());
            }
            case SPLIT -> {
                method = ORDER_SETTLE;
                biz = royalty(c, sub, platformAccount, "平台服务费");
            }
            // 「退营销补差」与「退分账」都走退款接口。接收方为个人的分账单不支持退分账 —— 调用方先看 split_reversible
            case SUBSIDY_RETURN, SPLIT_REVERSE -> {
                method = REFUND;
                biz = new LinkedHashMap<>();
                biz.put("trade_no", c.channelTradeNo());
                biz.put("out_request_no", c.requestNo());
                biz.put("refund_amount", yuan(c.amount().minor()));
                biz.put("refund_reason", c.kind() == SplitCommand.Kind.SPLIT_REVERSE ? "分账回退" : c.description());
            }
            default -> {
                return ChannelCapability.unsupported(ChannelOperation.SPLIT);
            }
        }
        Map<String, Object> resp = client.call(inv, method, biz);
        if (!OK.equals(String.valueOf(resp.get("code")))) {
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, String.valueOf(resp.get("sub_code")),
                    failure(method, resp), false);
        }
        String id = idOf(resp);
        return id == null ? new ChannelResult.Unknown("SPLIT_RECEIPT_WITHOUT_ID: " + method)
                : new ChannelResult.Succeeded(id, c.amount(), null, Map.of());
    }

    private static Map<String, Object> royalty(SplitCommand c, String transOut, String transIn, String desc) {
        Map<String, Object> royalty = new LinkedHashMap<>();
        royalty.put("royalty_type", "transfer");
        royalty.put("trans_out", transOut);
        royalty.put("trans_out_type", "userId");
        royalty.put("trans_in", transIn);
        royalty.put("trans_in_type", "userId");
        royalty.put("amount", yuan(c.amount().minor()));
        royalty.put("desc", desc == null ? "" : desc);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("trade_no", c.channelTradeNo());
        biz.put("out_request_no", c.requestNo());
        biz.put("royalty_parameters", List.of(royalty));
        return biz;
    }

    /** 分账 / 退款回执有时给 {@code trade_no}、有时只给 {@code out_request_no}：一个字段带一个兜底 */
    private static String idOf(Map<String, Object> resp) {
        Object id = resp.getOrDefault("trade_no", resp.get("out_request_no"));
        return id == null ? null : String.valueOf(id);
    }

    private static String failure(String method, Map<String, Object> resp) {
        String sub = String.valueOf(resp.get("sub_code"));
        return method + " code=" + resp.get("code") + " sub_code=" + sub
                + (sub.contains("INVALID-METHOD") ? "（接口名可能不对，不是参数问题）" : "");
    }

    private static ChannelResult reject(String reason) {
        return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, null, reason, false);
    }

    /** 支付宝金额是元、两位小数的字符串；我方一律最小单位 */
    static String yuan(long minor) {
        return String.format("%d.%02d", minor / 100, Math.abs(minor % 100));
    }
}
