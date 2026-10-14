package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PaymentLifecycleServiceImpl implements PaymentLifecycleService {
    private final SettlePort settlement;

    public PaymentLifecycleServiceImpl(SettlePort settlement) {
        this.settlement = settlement;
    }

    @Override
    public ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo) {
        if (OrdOrder.WAIT_OFFLINE_PAY.equals(order.getStatus()) && PayModes.OFFLINE.equals(channel)) {
            return new ConfirmedPayment(channel, tradeNo);
        }
        var receipt = settlement.successPayment(order.getOrderNo());
        if (receipt == null || !"SUCCESS".equals(receipt.status())
                || !Objects.equals(order.getOrderNo(), receipt.orderNo())
                || !Objects.equals(order.getUserNo(), receipt.userNo())
                || order.getPayAmount() == null || order.getPayAmount() != receipt.amountMinor()
                || !"CNY".equals(receipt.currency())
                || receipt.tradeNo() == null || receipt.tradeNo().isBlank()
                || receipt.payChannel() == null || receipt.payChannel().isBlank()
                || channel != null && !Objects.equals(channel, receipt.payChannel())
                || tradeNo != null && !Objects.equals(tradeNo, receipt.tradeNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new ConfirmedPayment(receipt.payChannel(), receipt.tradeNo());
    }
}
