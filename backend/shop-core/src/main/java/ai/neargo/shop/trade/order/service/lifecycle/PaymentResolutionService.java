package ai.neargo.shop.trade.order.service.lifecycle;

import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.contract.PaymentResolutionView;

public interface PaymentResolutionService {
    void record(OrdOrder order, SettlePort.PaymentReceipt receipt, String reason);
    PaymentResolutionView view(String payNo, String buyerNo);
}
