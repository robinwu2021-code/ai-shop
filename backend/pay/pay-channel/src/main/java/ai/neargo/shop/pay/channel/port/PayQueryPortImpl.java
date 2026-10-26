package ai.neargo.shop.pay.channel.port;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.channel.legacy.LegacyChannelBridge;
import ai.neargo.shop.spi.pay.PayQueryPort;
import ai.neargo.shop.spi.pay.RefundQueryResult;
import org.springframework.stereotype.Component;

/**
 * {@link PayQueryPort} 实现：把渠道的查单结果翻译成一个判断。
 *
 * <p><b>通道没接入时返回「查询失败」而不是「没有这笔」</b>：
 * 这两者对调用方的后果完全相反 —— 后者会让对账去关单，
 * 而通道没接入不代表用户没付钱。
 */
@Component
public class PayQueryPortImpl implements PayQueryPort {

    /** 渠道层（W2 S5：不再有按渠道名的路由表，查单走管线、查退款直达处理器，见桥的类注释） */
    private final LegacyChannelBridge channels;

    public PayQueryPortImpl(LegacyChannelBridge channels) {
        this.channels = channels;
    }

    @Override
    public Result query(String payChannel, String outTradeNo) {
        if (payChannel == null || !channels.supports(payChannel)) {
            // 通道未接入 ≠ 通道说没有这笔。前者不可据以关单
            return new Result(false, false, false, 0, null);
        }
        PayGateway.QueryResult r = channels.query(payChannel, outTradeNo);
        return new Result(r.ok(), r.paid(), r.found(), r.amountMinor(), r.tradeNo(), r.currency());
    }

    @Override
    public Result queryRefund(String payChannel, String outTradeNo) {
        if (payChannel == null || !channels.supports(payChannel)) {
            // 同 query：通道未接入 ≠ 通道说没有这笔。前者不可据以关单
            return new Result(false, false, false, 0, null);
        }
        PayGateway.QueryResult r = channels.queryRefund(payChannel, outTradeNo);
        return new Result(r.ok(), r.paid(), r.found(), r.amountMinor(), r.tradeNo(), r.currency());
    }

    @Override
    public RefundQueryResult queryRefundOutcome(String channel, String no) {
        if (channel == null || !channels.supports(channel)) {
            return RefundQueryResult.unknown(no);
        }
        return channels.queryRefundOutcome(channel, no);
    }
}
