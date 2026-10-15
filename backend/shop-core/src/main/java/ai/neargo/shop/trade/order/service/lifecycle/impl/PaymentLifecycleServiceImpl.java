package ai.neargo.shop.trade.order.service.lifecycle.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentLifecycleService;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import ai.neargo.shop.trade.order.entity.OrdPaymentResolution;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class PaymentLifecycleServiceImpl implements PaymentLifecycleService {
    private final SettlePort settlement;
    private final PaymentResolutionService resolutions;

    public PaymentLifecycleServiceImpl(SettlePort settlement, PaymentResolutionService resolutions) {
        this.settlement = settlement;
        this.resolutions = resolutions;
    }

    @Override
    public ConfirmedPayment requireConfirmed(OrdOrder order, String channel, String tradeNo) {
        if (OrdOrder.WAIT_OFFLINE_PAY.equals(order.getStatus()) && PayModes.OFFLINE.equals(channel)) {
            return new ConfirmedPayment(channel, tradeNo);
        }
        var receipt = tradeNo == null ? settlement.successPayment(order.getOrderNo())
                : settlement.successPayment(order.getOrderNo(), channel, tradeNo);
        if (receipt == null || !"SUCCESS".equals(receipt.status())
                || !Objects.equals(order.getOrderNo(), receipt.orderNo())
                || !Objects.equals(order.getUserNo(), receipt.userNo())
                || order.getPayAmount() == null || order.getPayAmount() != receipt.amountMinor()
                || !"CNY".equals(receipt.currency())
                || receipt.tradeNo() == null || receipt.tradeNo().isBlank()
                || receipt.payChannel() == null || receipt.payChannel().isBlank()
                || channel != null && !Objects.equals(channel, receipt.payChannel())
                || tradeNo != null && !Objects.equals(tradeNo, receipt.tradeNo())) {
            resolutions.record(order, receipt, OrdPaymentResolution.RECEIPT_MISMATCH);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (OrdOrder.CANCELLED.equals(order.getStatus()) || OrdOrder.CLOSED.equals(order.getStatus())) {
            resolutions.record(order, receipt, OrdPaymentResolution.LATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        if (OrdOrder.PAID.equals(order.getStatus()) && order.getPayTradeNo() != null
                && !Objects.equals(order.getPayTradeNo(), receipt.tradeNo())) {
            resolutions.record(order, receipt, OrdPaymentResolution.DUPLICATE_PAYMENT);
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return new ConfirmedPayment(receipt.payChannel(), receipt.tradeNo());
    }
}
