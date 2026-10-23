package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.spi.settle.RefundDispatchResult;
import ai.neargo.shop.spi.settle.SettlePort.PaymentReceipt;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;

/** Selects the original financial owner using persisted source binding, never a deployment guess. */
public interface RefundFinancialOwnerService {
    /** Local immutable source/economics digest; null means the original local retail owner. */
    String originFingerprint(OrdAfterSale afterSale, OrdSubOrder child, OrdOrder parent);
    PaymentReceipt origin(OrdAfterSale afterSale, OrdSubOrder child, OrdOrder parent);
    RefundDispatchResult request(OrdRefundExecution execution);
    RefundDispatchResult query(OrdRefundExecution execution);
}
