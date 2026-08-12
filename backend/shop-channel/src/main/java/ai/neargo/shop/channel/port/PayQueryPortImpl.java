package ai.neargo.shop.channel.port;

import ai.neargo.shop.channel.pay.PayGateway;
import ai.neargo.shop.channel.pay.PayGatewayRouter;

import ai.neargo.shop.spi.pay.PayQueryPort;
import org.springframework.stereotype.Component;

/**
 * {@link PayQueryPort} 实现：把通道的查单结果翻译成一个判断。
 *
 * <p><b>通道没接入时返回「查询失败」而不是「没有这笔」</b>：
 * 这两者对调用方的后果完全相反 —— 后者会让对账去关单，
 * 而通道没接入不代表用户没付钱。
 */
@Component
public class PayQueryPortImpl implements PayQueryPort {

    private final PayGatewayRouter router;

    public PayQueryPortImpl(PayGatewayRouter router) {
        this.router = router;
    }

    @Override
    public Result query(String payChannel, String outTradeNo) {
        if (payChannel == null || !router.supports(payChannel)) {
            // 通道未接入 ≠ 通道说没有这笔。前者不可据以关单
            return new Result(false, false, false, 0, null);
        }
        PayGateway.QueryResult r = router.of(payChannel).query(outTradeNo);
        return new Result(r.ok(), r.paid(), r.found(), r.amountMinor(), r.tradeNo());
    }
}
