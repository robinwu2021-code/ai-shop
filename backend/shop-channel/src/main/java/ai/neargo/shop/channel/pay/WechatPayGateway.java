package ai.neargo.shop.channel.pay;

import ai.neargo.shop.channel.pay.ChannelClient.ChannelException;
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
public class WechatPayGateway implements PayGateway {

    private final ChannelClient client;

    public WechatPayGateway(@Qualifier("wechatChannelClient") ChannelClient client) {
        this.client = client;
    }

    @Override
    public String payChannel() {
        return "WECHAT";
    }

    @Override
    public Result subsidy(TxContext ctx, long amountMinor, String requestNo, String description) {
        // 时序硬约束：**订单支付成功并结算完成后、发起分账前**。
        // 早了通道拒绝；晚了分账基数不含补贴，商家少收而账面看不出来
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("transaction_id", ctx.tradeNo());
        body.put("amount", amountMinor);
        body.put("description", trim(description, 80));
        body.put("out_subsidy_no", requestNo);
        return call(WechatApis.SUBSIDY_CREATE, body, "subsidy_id");
    }

    @Override
    public Result subsidyReturn(TxContext ctx, long amountMinor, String requestNo, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("subsidy_id", ctx.tradeNo());
        body.put("out_order_no", requestNo);
        body.put("amount", amountMinor);
        body.put("description", trim(description, 80));
        return call(WechatApis.SUBSIDY_RETURN, body, "refund_id");
    }

    @Override
    public Result split(TxContext ctx, long amountMinor, String requestNo) {
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
        return call(WechatApis.PROFIT_SHARING, body, "order_id");
    }

    @Override
    public Result splitReverse(TxContext ctx, long amountMinor, String requestNo) {
        // **部分回退**，不是全额回退再重分 —— 微信回退后不支持重新发起分账。
        // 支持多次，总额不超过原分账单分给该接收方的金额
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sub_mchid", ctx.subMchId());
        body.put("order_id", ctx.tradeNo());
        body.put("out_return_no", requestNo);
        body.put("amount", amountMinor);
        body.put("description", "退款回退");
        return call(WechatApis.PROFIT_SHARING_RETURN, body, "return_id");
    }

    @Override
    public Result refund(TxContext ctx, long amountMinor, String requestNo, String reason) {
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
        return call(WechatApis.REFUND, body, "refund_id");
    }

    // ---------------------------------------------------------------- 内部

    private Result call(String api, Map<String, Object> body, String idField) {
        try {
            Map<String, Object> resp = client.post(api, body);
            Object id = resp.get(idField);
            // 微信补差返回 result：SUCCESS / FAIL / REFUND。
            // 只有 SUCCESS 才算成功 —— FAIL 当成功的话，分账会在余额不足时炸
            Object result = resp.get("result");
            if (result != null && !"SUCCESS".equals(result)) {
                return Result.fatal(api + " 返回 result=" + result);
            }
            return id == null ? Result.fatal(api + " 未返回 " + idField) : Result.ok(String.valueOf(id));
        } catch (ChannelException e) {
            return e.isRetryable() ? Result.retry(e.getMessage()) : Result.fatal(e.getMessage());
        }
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
