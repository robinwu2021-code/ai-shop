package ai.neargo.shop.paybridge;

import ai.neargo.shop.pay.channel.followup.FollowUpResolver;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderRepairPort;
import org.springframework.stereotype.Component;

/**
 * 补查查到「已付」→ 入账（TDD-支付服务-I2渠道统一封装 S5）。顺序与渠道回调逐字一致：<b>支付域的账先落，再改订单</b>；
 * 两边都按我方单号幂等，补查与回调同时到达只入账一次（AC9）。
 */
@Component
public class PaymentFollowUpResolver implements FollowUpResolver {

    private final SettlePort settle;
    private final OrderRepairPort orders;

    public PaymentFollowUpResolver(SettlePort settle, OrderRepairPort orders) {
        this.settle = settle;
        this.orders = orders;
    }

    @Override
    public boolean paid(String payChannel, String outTradeNo, String channelTradeNo, long amountMinor, String currency) {
        String orderNo = settle.settlePayment(new SettlePort.PaymentSettled(outTradeNo, payChannel, channelTradeNo,
                amountMinor, System.currentTimeMillis(), currency));
        if (orderNo == null) {
            return false;
        }
        orders.markPaid(orderNo, payChannel, channelTradeNo);
        return true;
    }
}
