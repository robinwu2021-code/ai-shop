package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.ChannelClient.ChannelException;
import ai.neargo.shop.pay.channel.base.AbstractPayGateway;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;

/**
 * 支付宝直付通网关。接口坐标见 {@link AlipayApis}。
 *
 * <p><b>与微信的两处结构性差异</b>，写在这里免得每次读都要重新对一遍：
 * <ol>
 *   <li>分账前要先 {@code alipay.trade.settle.confirm} 确认结算，<b>之后等 30 秒</b>
 *       —— 微信没有这一步</li>
 *   <li><b>补差没有独立接口</b>，是把分账的资金方向反过来
 *       （{@code trans_out} = 平台账户）。这是<b>推断</b>，见 {@link AlipayApis#SUBSIDY}</li>
 * </ol>
 */
@Component
// 用**显式开关**而不是「凭据配了没」来判断是否装配：
// `${ENV:}` 在未配置时是空串，而 @ConditionalOnProperty 认为「键存在」即成立 ——
// 于是网关会带着空凭据启动，直到第一次调用才失败
@ConditionalOnProperty(name = "shop.pay.alipay.enabled", havingValue = "true")
public class AlipayPayGateway extends AbstractPayGateway {

    /** 平台自己的收款账号，补差时作为出资方（{@code trans_out}）。 */
    private final String platformAccount;

    public AlipayPayGateway(@Qualifier("alipayChannelClient") ChannelClient client,
                            PayChannelMasterService channelMaster,
                            @Value("${shop.pay.alipay.platform-account:}") String platformAccount) {
        super(client, channelMaster);
        this.platformAccount = platformAccount;
    }

    @Override
    public String payChannel() {
        return "ALIPAY";
    }

    /**
     * 补差：把平台账户的钱分给二级商户 —— <b>反向的分账</b>。
     *
     * <p>⚠️ <b>联调第一件事就是验证这个假设。</b> 如果通道返回的是「参数错误」，
     * 说明接口名对、参数要调；如果返回「服务不存在」或「无权限」，
     * 说明<b>补差另有接口</b>，那时要回头改 {@link AlipayApis#SUBSIDY}。
     * 这两种错误的区分很重要 —— 混为一谈会让人在参数上耗很久。
     */
    @Override
    protected Call buildSubsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        if (platformAccount.isBlank()) {
            return reject("未配置 shop.pay.alipay.platform-account，补差没有出资方");
        }
        Map<String, Object> body = royalty(ctx, requestNo,
                platformAccount, ctx.subMchId(), amountMinor, description);
        return new Call(AlipayApis.SUBSIDY, body, "trade_no");
    }

    @Override
    protected Call buildSubsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        // 「退营销补差」走退款接口，与退分账并列 —— 支付宝文档把三件事列在一起
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trade_no", ctx.tradeNo());
        body.put("out_request_no", requestNo);
        body.put("refund_amount", yuan(amountMinor));
        body.put("refund_reason", description);
        return new Call(AlipayApis.REFUND, body, "trade_no");
    }

    /**
     * 分账：从二级商户分给平台。
     *
     * <p><b>调用方必须先做确认结算并等满 30 秒</b> —— 这一步不在这里做，
     * 因为它是每单一次的状态迁移，属于结算流程；放在这里会变成每次分账都确认一遍。
     */
    @Override
    protected Call buildSplit(TxContext ctx, long amountMinor, String requestNo) {
        Map<String, Object> body = royalty(ctx, requestNo,
                ctx.subMchId(), platformAccount, amountMinor, "平台服务费");
        return new Call(AlipayApis.ORDER_SETTLE, body, "trade_no");
    }

    @Override
    protected Call buildSplitReverse(TxContext ctx, long amountMinor, String requestNo) {
        // 支付宝的退分账走退款接口。**接收方为个人的分账单不支持** ——
        // 调用方应先看 mch_payment_merchant.split_reversible，别等通道拒绝
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trade_no", ctx.tradeNo());
        body.put("out_request_no", requestNo);
        body.put("refund_amount", yuan(amountMinor));
        body.put("refund_reason", "分账回退");
        return new Call(AlipayApis.REFUND, body, "trade_no");
    }

    @Override
    protected Call buildRefund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        // 多次退款**必须传不同的 out_request_no** —— requestNo 已保证唯一
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trade_no", ctx.tradeNo());
        body.put("out_trade_no", ctx.outTradeNo());
        body.put("out_request_no", requestNo);
        body.put("refund_amount", yuan(amountMinor));
        body.put("refund_reason", reason);
        return new Call(AlipayApis.REFUND, body, "trade_no");
    }

    // ---------------------------------------------------------------- 内部

    /** 组一条 {@code royalty_parameters}。方向由 transOut/transIn 决定：分账或补差。 */
    private Map<String, Object> royalty(TxContext ctx, String requestNo,
                                        String transOut, String transIn,
                                        long amountMinor, String desc) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trade_no", ctx.tradeNo());
        body.put("out_request_no", requestNo);
        body.put("royalty_parameters", List.of(new LinkedHashMap<>(Map.of(
                "royalty_type", "transfer",
                "trans_out", transOut,
                "trans_out_type", "userId",
                "trans_in", transIn,
                "trans_in_type", "userId",
                "amount", yuan(amountMinor),
                "desc", desc == null ? "" : desc))));
        return body;
    }

    /** 支付宝金额单位是**元**（两位小数），我们内部一律用分 —— 只在出口处换。 */
    private static String yuan(long minor) {
        return String.format("%d.%02d", minor / 100, Math.abs(minor % 100));
    }

    /**
     * 支付宝的成功判据：{@code code=10000}。
     *
     * <p><b>区分「服务不存在」与「参数错误」</b>：前者说明接口名不对（见 subsidy 的注释），
     * 后者才是参数问题。两者都不可重试，但排查方向完全不同。
     */
    @Override
    protected String failureOf(String api, Map<String, Object> resp) {
        Object code = resp.get("code");
        if ("10000".equals(String.valueOf(code))) {
            return null;
        }
        String sub = String.valueOf(resp.get("sub_code"));
        boolean unknownApi = sub != null && sub.contains("INVALID-METHOD");
        return api + " code=" + code + " sub_code=" + sub
                + (unknownApi ? "（**接口名可能不对**，不是参数问题）" : "");
    }

    /** 分账/退款回执有时给 {@code trade_no}、有时只给 {@code out_request_no}。 */
    @Override
    protected Object idOf(Call c, Map<String, Object> resp) {
        return resp.getOrDefault("trade_no", resp.get("out_request_no"));
    }

    /**
     * 查单。支付宝的 {@code trade_status} 里<b>两个值都算已支付</b>：
     * TRADE_SUCCESS（可退款）与 TRADE_FINISHED（已完成、不可退款）——
     * 只认前者的话，超过退款期的老单会被判成未支付，然后被关掉。
     *
     * <p>交易不存在时支付宝返回 {@code ACQ.TRADE_NOT_EXIST}，
     * 那是「通道没有这笔」，与查询失败不同。
     */
    @Override
    public QueryResult query(String outTradeNo) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("out_trade_no", outTradeNo);
            Map<String, Object> resp = client.post(AlipayApis.TRADE_QUERY, body);
            Object sub = resp.get("sub_code");
            if (sub != null && String.valueOf(sub).contains("TRADE_NOT_EXIST")) {
                return QueryResult.notFound();
            }
            Object status = resp.get("trade_status");
            if (!"TRADE_SUCCESS".equals(status) && !"TRADE_FINISHED".equals(status)) {
                return QueryResult.unpaid();
            }
            // 支付宝金额是元（字符串），我方一律最小单位
            Object amount = resp.get("total_amount");
            long minor = amount == null ? 0L
                    : new java.math.BigDecimal(String.valueOf(amount))
                            .movePointRight(2).longValueExact();
            return QueryResult.paid(minor, String.valueOf(resp.get("trade_no")));
        } catch (ChannelClient.ChannelException e) {
            return QueryResult.failed();
        } catch (ArithmeticException | NumberFormatException e) {
            // 金额解析不出来时**不当作已支付** —— 宁可下一轮再查
            return QueryResult.failed();
        }
    }
}
