package ai.neargo.shop.pay.channel;

import ai.neargo.shop.pay.channel.base.AbstractPayGateway;
import ai.neargo.shop.pay.channel.master.PayChannelMasterService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付电商收付通网关。接口坐标见 {@link WechatApis}。
 *
 * <p>五个动作的时序：<b>支付 → 补差 → 分账</b>，退款时反着来
 * <b>分账回退 → 补差回退 → 退款</b>。顺序写反的表现是通道报错，
 * 而那时钱已经在二级商户的冻结账户里了。
 */
@Component
// 用**显式开关**而不是「凭据配了没」来判断是否装配：
// `${ENV:}` 在未配置时是空串，而 @ConditionalOnProperty 认为「键存在」即成立 ——
// 于是网关会带着空凭据启动，直到第一次调用才失败
@ConditionalOnProperty(name = "shop.pay.wechat.enabled", havingValue = "true")
public class WechatPayGateway extends AbstractPayGateway {

    public WechatPayGateway(@Qualifier("wechatChannelClient") ChannelClient client,
                            PayChannelMasterService channelMaster) {
        super(client, channelMaster);
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    @Override
    protected Call buildSubsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        // 时序硬约束：**订单支付成功并结算完成后、发起分账前**。
        // 早了通道拒绝；晚了分账基数不含补贴，商家少收而账面看不出来
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("transaction_id", ctx.tradeNo());
        body.put("amount", amountMinor);
        body.put("description", trim(description, 80));
        body.put("out_subsidy_no", requestNo);
        return new Call(WechatApis.SUBSIDY_CREATE, body, "subsidy_id");
    }

    @Override
    protected Call buildSubsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("subsidy_id", ctx.tradeNo());
        body.put("out_order_no", requestNo);
        body.put("amount", amountMinor);
        body.put("description", trim(description, 80));
        return new Call(WechatApis.SUBSIDY_RETURN, body, "refund_id");
    }

    @Override
    protected Call buildSplit(TxContext ctx, long amountMinor, String requestNo) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("transaction_id", ctx.tradeNo());
        body.put("out_order_no", requestNo);
        // 单笔最多分账 50 次、每次最多 50 个接收方。我们一次只分给平台一个接收方
        body.put("receivers", java.util.List.of(Map.of(
                "type", "MERCHANT_ID",
                "amount", amountMinor,
                "description", "平台服务费")));
        body.put("finish", false);
        return new Call(WechatApis.PROFIT_SHARING, body, "order_id");
    }

    @Override
    protected Call buildSplitReverse(TxContext ctx, long amountMinor, String requestNo) {
        // **部分回退**，不是全额回退再重分 —— 微信回退后不支持重新发起分账。
        // 支持多次，总额不超过原分账单分给该接收方的金额
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("order_id", ctx.tradeNo());
        body.put("out_return_no", requestNo);
        body.put("amount", amountMinor);
        body.put("description", "退款回退");
        return new Call(WechatApis.PROFIT_SHARING_RETURN, body, "return_id");
    }

    @Override
    protected Call buildRefund(TxContext ctx, long amountMinor, String requestNo, String reason) {
        // 单笔最多部分退款 50 次，多次需换退款单号（requestNo 已保证唯一），
        // 且两次调用间隔 >= 60 秒 —— 排队由调用方按 sys_pay_channel 的限额控制
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("transaction_id", ctx.tradeNo());
        body.put("out_refund_no", requestNo);
        body.put("reason", trim(reason, 80));
        body.put("amount", Map.of(
                "refund", amountMinor,
                "total", ctx.totalMinor(),
                "currency", "CNY"));
        return new Call(WechatApis.REFUND, body, "refund_id");
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 查单。<b>只有 {@code trade_state=SUCCESS} 算已支付</b> ——
     * USERPAYING（用户支付中）当成已支付的话，会给一笔还没付的单发货；
     * 而 NOTPAY / CLOSED 是「通道有这笔但没付」，与「通道根本没这笔」不同：
     * 前者不能补单，后者可以安全关单。
     */
    @Override
    public QueryResult query(String outTradeNo) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("out_trade_no", outTradeNo);
            Map<String, Object> resp = client.post(
                    WechatApis.TRANSACTION_BY_OUT_TRADE_NO + outTradeNo, body);
            Object state = resp.get("trade_state");
            if (state == null) {
                // 通道没有这笔（微信对不存在的单返回 ORDERNOTEXIST）
                return QueryResult.notFound();
            }
            if (!"SUCCESS".equals(state)) {
                return QueryResult.unpaid();
            }
            Object amount = resp.get("amount");
            long minor = amount instanceof Map<?, ?> m && m.get("total") instanceof Number n
                    ? n.longValue() : 0L;
            return QueryResult.paid(minor, String.valueOf(resp.get("transaction_id")));
        } catch (ChannelClient.ChannelException e) {
            // 查询失败 ≠ 没有这笔。返回 failed 让对账留到下一轮，别关单
            return QueryResult.failed();
        }
    }

    /**
     * 微信的成功判据：{@code result} 缺省或 {@code SUCCESS}。
     * <b>FAIL 当成功的话，分账会在余额不足时炸</b>，而炸的时候补差已经发出去了。
     */
    @Override
    protected String failureOf(String api, Map<String, Object> resp) {
        Object result = resp.get("result");
        return result != null && !"SUCCESS".equals(result)
                ? api + " 返回 result=" + result : null;
    }

}
