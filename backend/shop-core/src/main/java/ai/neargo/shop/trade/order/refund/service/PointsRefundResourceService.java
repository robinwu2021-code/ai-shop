package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import java.util.List;

/** Source-owned inventory tasks are independent from consumer refund settlement. */
public interface PointsRefundResourceService {
    void prepare(OrdAfterSale afterSale,RefundPlan plan);
    /** @return true when inventory restoration is confirmed by the local task state. */
    boolean recover(String afterSaleNo);
    List<String> pendingNos(int limit);
}
