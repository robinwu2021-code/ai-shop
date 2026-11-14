package ai.neargo.shop.pay.channel.wechat;

import ai.neargo.shop.pay.channel.api.ChannelResult;
import ai.neargo.shop.pay.channel.api.Money;
import ai.neargo.shop.pay.channel.api.PayErrorCode;
import ai.neargo.shop.spi.pay.PayInstruction;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** 微信两种接入模式共用的应答 → 四态映射。判据自原网关逐字搬来，放一处免得直连与收付通走岔 */
public final class WechatResults {

    private WechatResults() {
    }

    /**
     * 查单应答：没有 {@code trade_state} → 渠道没有这笔；不是 SUCCESS → 有这笔未付；SUCCESS → 已付（带渠道金额与<b>实际币种</b>，
     * 币种缺失不编造）。
     */
    public static ChannelResult trade(String originalNumber, Map<String, Object> resp) {
        Object state = resp.get("trade_state");
        if (state == null) {
            return new ChannelResult.Failed(PayErrorCode.TRADE_NOT_FOUND, null, "渠道没有这笔", false);
        }
        String tradeNo = resp.get("transaction_id") instanceof String t ? t : null;
        if (!"SUCCESS".equals(state)) {
            return new ChannelResult.Pending(PayInstruction.none(), tradeNo, String.valueOf(state));
        }
        if (!originalNumber.equals(resp.get("out_trade_no")) || tradeNo == null || tradeNo.isBlank()
                || !(resp.get("amount") instanceof Map<?, ?> amount)
                || !(amount.get("total") instanceof Number total)
                || !(amount.get("currency") instanceof String currency) || currency.isBlank()) {
            return new ChannelResult.Unknown("PAYMENT_RECEIPT_MISMATCH");
        }
        try {
            long minor = new BigDecimal(total.toString()).longValueExact();
            if (minor < 0) return new ChannelResult.Unknown("PAYMENT_RECEIPT_INVALID_AMOUNT");
            return new ChannelResult.Succeeded(tradeNo, new Money(minor, currency), null,
                    Map.of("channelStatus", "SUCCESS"));
        } catch (ArithmeticException | NumberFormatException invalidMoney) {
            return new ChannelResult.Unknown("PAYMENT_RECEIPT_INVALID_AMOUNT");
        }
    }

    /**
     * 退款的受理回执，与原 {@code AbstractPayGateway#exec} 经 I2 过渡适配后的口径一致：
     * 2xx 里带错误码 / {@code result} 不是 SUCCESS → 渠道答了但说不清（<b>不是明确拒绝</b>，只查不重发，{@code REFUND_SEND_UNPROVEN}）；
     * 缺渠道单号 → 不明（{@code REFUND_SEND_UNKNOWN}）；有单号 → 受理。
     */
    public static ChannelResult accepted(Map<String, Object> resp, String idField) {
        Object code = resp.get("code");
        Object result = resp.get("result");
        if (code != null || (result != null && !"SUCCESS".equals(result))) {
            return new ChannelResult.Unknown("REFUND_SEND_UNPROVEN: 渠道回执带错误 "
                    + (code != null ? "code=" + code : "result=" + result));
        }
        Object id = resp.get(idField);
        if (id == null) {
            return new ChannelResult.Unknown("REFUND_SEND_UNKNOWN: 回执未返回 " + idField);
        }
        return new ChannelResult.Pending(PayInstruction.none(), String.valueOf(id),
                resp.get("status") instanceof String s ? s : null);
    }

    /**
     * 补差 / 分账 / 回退的回执：{@code result} 不是 SUCCESS 或带错误码 → 渠道明确不做（与原网关「fatal、不可重试」同口径）；
     * 缺渠道单号 → 不明（钱可能动了，不重发）；有单号 → 成功。
     */
    public static ChannelResult splitDone(Map<String, Object> resp, String idField, Money amount) {
        Object code = resp.get("code");
        Object result = resp.get("result");
        if (code != null || (result != null && !"SUCCESS".equals(result))) {
            String channelCode = String.valueOf(code != null ? code : result);
            return new ChannelResult.Failed(PayErrorCode.CHANNEL_REJECTED, channelCode, "渠道回执 " + channelCode, false);
        }
        Object id = resp.get(idField);
        if (id == null) {
            return new ChannelResult.Unknown("SPLIT_RECEIPT_WITHOUT_ID: 回执未返回 " + idField);
        }
        return new ChannelResult.Succeeded(String.valueOf(id), amount, null, Map.of());
    }

    public static String currencyOf(Map<String, Object> resp) {
        return resp.get("amount") instanceof Map<?, ?> m && m.get("currency") instanceof String c ? c : null;
    }

    public static long refundAmountOf(Map<String, Object> resp) {
        return resp.get("amount") instanceof Map<?, ?> m && m.get("refund") instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 查退款成功时带给桥的原始字段：我方退款单号、原始状态、退款金额（渠道没给币种时 {@code Money} 造不出来，金额仍要留着）。
     * 五态回执（{@code RefundQueryResult}）要它们。
     */
    public static Map<String, String> extra(String outRefundNo, String rawStatus, long amountMinor) {
        Map<String, String> m = new HashMap<>();
        m.put("outRefundNo", outRefundNo);
        m.put("amountMinor", String.valueOf(amountMinor));
        if (rawStatus != null) {
            m.put("channelStatus", rawStatus);
        }
        return m;
    }

    /** 通道对描述字段都有长度上限，超了直接报参数错 */
    public static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
